package models.analytics.theworks

// external
import akka.actor.ActorLogging
import akka.contrib.pattern.ReceivePipeline
import akka.contrib.pattern.ReceivePipeline.Inner


// internal
import models.market.MarketEMACollection
import models.market.MarketStructures.{Candles, ClosePrice, ExponentialMovingAverage, MarketMessage}

trait ExponentialMovingAverages extends ActorLogging {

  this: ReceivePipeline => pipelineInner {
    case msg: MarketMessage =>
      val marketName = msg.cryptoCurrency
      val currentPrice = msg.last
      if (averages.contains(marketName)) {
        averages(marketName).foreach(_.updateAverages(ClosePrice(msg.time, currentPrice)))
      }
      Inner(msg)

    /**
      * Assumes that the candles are orderd by latest time first!
      */
    case mc: Candles =>
      val closePrices = mc.candles.map( c => ClosePrice(c.time, c.close))
      setAverages(mc.marketName, closePrices)
      Inner(mc)
  }

  // these are the system defaults but can be overridden
  val periods: List[Int] = List(7, 15)
  val periodMinutes = 5

  // map of marketName -> list of ema collections
  private val averages = scala.collection.mutable.Map[String, List[MarketEMACollection]]()

  def allAverages = averages.toMap

  def setAllMarketAverages(marketAverages: Map[String, List[MarketEMACollection]]) = averages ++= marketAverages

  /**
    * Sets all averages for each period as a collection of exponential
    * moving averages.
    *
    * @param marketName
    * @param closePrices assumes the close prices are sorted with most recent
    *                    close period first and that all close prices have the same periodMinutes
    *                    as this object. Example, close prices for every 5 minute
    *                    period for the last 24 hour window.
    * @return
    */
  def setAverages(marketName: String, closePrices: List[ClosePrice]) = {
    // create a new collection for specific periods
    val averagesList = for (period <- periods)
      yield new MarketEMACollection(marketName, period, periodMinutes, closePrices)

    averages.put(marketName, averagesList)
  }

  /**
    * Returns a List[(Int, BigDecimal)] to the sender
    */
  def getLatestMovingAverages(marketName: String): List[(Int, BigDecimal)] = {
    averages.get(marketName) match {
      case Some(avgs) =>
        avgs.map( a => (a.period, a.emas.head.ema))
      case None => List[(Int, BigDecimal)]()
    }
  }

  /**
    * Send to original sender the moving averages of a market.
    * retunrs a List[(Int, List[ExponentialMovingAverage])]
    */
  def getMovingAverages(marketName: String): List[(Int, List[ExponentialMovingAverage])] = {
    averages.get(marketName) match {
      case Some(avgs) => avgs.map( a => (a.period, a.emas))
      case None => List[(Int, List[ExponentialMovingAverage])]()
    }
  }

  def getMovingAverages2(marketName: String): Map[Int, List[ExponentialMovingAverage]] = {
    averages.get(marketName) match {
      case Some(avgs) => avgs.map( a => a.period -> a.emas).toMap
      case None => Map.empty[Int, List[ExponentialMovingAverage]]
    }
  }
}
