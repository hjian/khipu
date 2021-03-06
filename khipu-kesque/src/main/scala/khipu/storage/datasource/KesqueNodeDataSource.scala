package khipu.storage.datasource

import java.util.concurrent.locks.ReentrantReadWriteLock
import kafka.server.LogAppendResult
import kafka.utils.Logging
import kesque.Kesque
import kesque.KesqueIndex
import khipu.Hash
import khipu.TKeyVal
import khipu.TVal
import khipu.crypto
import khipu.util.FIFOCache
import org.apache.kafka.common.record.CompressionType
import org.apache.kafka.common.record.DefaultRecord
import org.apache.kafka.common.record.DefaultRecordBatch
import org.apache.kafka.common.record.SimpleRecord

final class KesqueNodeDataSource(
    val topic:       String,
    kesqueDb:        Kesque,
    index:           KesqueIndex,
    cacheSize:       Int,
    fetchMaxBytes:   Int             = kesque.DEFAULT_FETCH_MAX_BYTES,
    compressionType: CompressionType = CompressionType.NONE
) extends NodeDataSource with Logging {
  type This = KesqueNodeDataSource

  private val cache = new FIFOCache[Hash, TVal](cacheSize)

  private val lock = new ReentrantReadWriteLock()
  private val readLock = lock.readLock
  private val writeLock = lock.writeLock

  info(s"Table $topic nodes $count")

  def get(key: Hash): Option[Array[Byte]] = getWithOffset(key, notCache = false).map(_.value)
  def getWithOffset(key: Hash, notCache: Boolean): Option[TVal] = {
    try {
      readLock.lock

      val keyBytes = key.bytes
      cache.get(key) match {
        case None =>
          val start = System.nanoTime

          var offsets = index.get(keyBytes)
          var foundValue: Option[TVal] = None
          while (foundValue.isEmpty && offsets.nonEmpty) {
            val offset = offsets.head
            val (topicPartition, result) = kesqueDb.read(topic, offset, fetchMaxBytes).head
            val recs = result.info.records.records.iterator
            // NOTE: the records usually do not start from the fecth-offset, 
            // the expected record may be near the tail of recs
            //println(s"\n======= $offset -> $result")
            while (recs.hasNext) {
              val rec = recs.next
              //print(s"${rec.offset},")
              if (rec.offset == offset) {
                if (rec.hasValue) {
                  val data = kesque.getBytes(rec.value)
                  val fullKey = crypto.kec256(data)
                  if (java.util.Arrays.equals(fullKey, keyBytes)) {
                    foundValue = Some(TVal(data, offset))
                  }
                } else {
                  None
                }
              }
            }
            offsets = offsets.tail
          }

          if (!notCache) {
            foundValue foreach { tval => cache.put(key, tval) }
          }

          clock.elapse(System.nanoTime - start)

          foundValue
        case Some(tval) => Some(tval)
      }

    } finally {
      readLock.unlock()
    }
  }

  def update(toRemove: Iterable[Hash], toUpsert: Iterable[(Hash, Array[Byte])]): This = {
    // we'll keep the batch size not exceeding fetchMaxBytes to get better random read performance
    var batchedRecords = Vector[(Seq[(Hash, Array[Byte])], Seq[SimpleRecord])]()

    var size = DefaultRecordBatch.RECORD_BATCH_OVERHEAD
    var offsetDelta = 0
    var firstTimestamp = Long.MinValue

    // prepare simple records
    var tkvs = Vector[(Hash, Array[Byte])]()
    var records = Vector[SimpleRecord]()
    toUpsert foreach {
      case kv @ (key, value) =>
        getWithOffset(key, false) match {
          case Some(TVal(value, offset)) =>
            // already exists, added refer count?
            ()

          case None =>
            val record = new SimpleRecord(null, value)
            if (firstTimestamp == Long.MinValue) {
              firstTimestamp = 0
            }
            val (newSize, estimatedSize) = estimateSizeInBytes(size, firstTimestamp, offsetDelta, record)
            //println(s"$newSize, $estimatedSize")
            if (estimatedSize < fetchMaxBytes) {
              tkvs :+= kv
              records :+= record

              size = newSize
              offsetDelta += 1
            } else {
              batchedRecords :+= (tkvs, records)
              tkvs = Vector[(Hash, Array[Byte])]()
              records = Vector[SimpleRecord]()

              tkvs :+= kv
              records :+= record

              size = DefaultRecordBatch.RECORD_BATCH_OVERHEAD
              offsetDelta = 0
              val (firstSize, _) = estimateSizeInBytes(size, firstTimestamp, offsetDelta, record)
              size = firstSize
              offsetDelta += 1
            }
        }
    }

    if (records.nonEmpty) {
      batchedRecords :+= (tkvs, records)
    }

    debug(s"toUpsert: ${toUpsert.size}, batchedRecords: ${batchedRecords.map(_._2.size).sum}")

    // write to log file
    batchedRecords map {
      case (tkvs, recs) =>
        if (recs.nonEmpty) {
          writeRecords(tkvs, recs)
        } else {
          0
        }
    } sum

    this
  }

  /**
   * @see org.apache.kafka.common.record.AbstractRecords#estimateSizeInBytes
   */
  private def estimateSizeInBytes(prevSize: Int, firstTimestamp: Long, offsetDelta: Int, record: SimpleRecord): (Int, Int) = {
    val timestampDelta = record.timestamp - firstTimestamp
    val size = prevSize + DefaultRecord.sizeInBytes(offsetDelta, timestampDelta, record.key, record.value, record.headers)

    val estimateSize = if (compressionType == CompressionType.NONE) {
      size
    } else {
      math.min(math.max(size / 2, 1024), 1 << 16)
    }
    (size, estimateSize)
  }

  private def writeRecords(kvs: Seq[(Hash, Array[Byte])], records: Seq[SimpleRecord]): Int = {
    try {
      writeLock.lock()

      // write simple records and create index records
      val indexRecords = kesqueDb.write(topic, records, compressionType).foldLeft(Vector[Vector[(Array[Byte], Long)]]()) {
        case (indexRecords, (topicPartition, LogAppendResult(appendInfo, Some(ex)))) =>
          error(ex.getMessage, ex) // TODO
          indexRecords

        case (indexRecords, (topicPartition, LogAppendResult(appendInfo, None))) =>
          if (appendInfo.numMessages > 0) {
            val firstOffert = appendInfo.firstOffset.get
            val (lastOffset, idxRecords) = kvs.foldLeft(firstOffert, Vector[(Array[Byte], Long)]()) {
              case ((longOffset, idxRecords), (key, value)) =>
                val offset = longOffset.toInt
                val keyBytes = key.bytes
                val indexRecord = (keyBytes -> longOffset)

                cache.put(key, TVal(value, offset))
                (offset + 1, idxRecords :+ indexRecord)
            }

            assert(appendInfo.lastOffset == lastOffset - 1, s"lastOffset(${appendInfo.lastOffset}) != ${lastOffset - 1}, firstOffset is ${appendInfo.firstOffset}, numOfMessages is ${appendInfo.numMessages}, numRecords is ${records.size}, appendInfo: $appendInfo")

            indexRecords :+ idxRecords
          } else {
            indexRecords
          }
      }

      // write index records
      index.put(indexRecords.flatten)

      indexRecords.map(_.size).sum
    } finally {
      writeLock.unlock()
    }
  }

  def readBatch(topic: String, fromOffset: Long, fetchMaxBytes: Int): (Long, Array[TKeyVal]) = {
    try {
      readLock.lock()

      kesqueDb.readBatch(topic, fromOffset, fetchMaxBytes)
    } finally {
      readLock.unlock()
    }
  }

  def count = kesqueDb.getLogEndOffset(topic)

  def cacheHitRate = cache.hitRate
  def cacheReadCount = cache.readCount
  def resetCacheHitRate() = cache.resetHitRate()

  def stop() {
    index.stop()
  }
}

