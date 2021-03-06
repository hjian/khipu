package khipu.storage

import khipu.DataWord
import khipu.storage.datasource.BlockDataSource
import khipu.util.SimpleMapWithUnconfirmed

/**
 * This class is used to store the total difficulty of blocks, by using:
 *   Key: hash of the block
 *   Value: the total difficulty
 */
final class TotalDifficultyStorage(val source: BlockDataSource, unconfirmedDepth: Int) extends SimpleMapWithUnconfirmed[Long, DataWord](unconfirmedDepth) {
  type This = TotalDifficultyStorage

  def topic = source.topic

  override protected def getFromSource(key: Long): Option[DataWord] = {
    source.get(key).map(DataWord.safe)
  }

  override protected def updateToSource(toRemove: Iterable[Long], toUpsert: Iterable[(Long, DataWord)]): This = {
    val upsert = toUpsert map {
      case (key, value) => (key -> value.bigEndianMag)
    }
    source.update(toRemove, upsert)
    this
  }
}

