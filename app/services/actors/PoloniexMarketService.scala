package services.actors

// external

import akka.actor.{Actor, ActorLogging}
import akka.contrib.pattern.ReceivePipeline
import akka.contrib.pattern.ReceivePipeline.Inner
import java.time.OffsetDateTime
import javax.inject.Inject
import play.api.Configuration

import scala.language.postfixOps

// internal
import models.analytics.{LastMarketMessage, Volume24HourTracking}
import models.market.MarketStructures.MarketMessage
import models.market.MarketStructures.{Candles => Can}
import models.poloniex.PoloniexEventBus
import models.poloniex.PoloniexEventBus._
import models.poloniex.{MarketEvent}
import models.market.MarketCandle
import models.analytics.MarketCandles
import models.analytics.Archiving
import models.analytics.ExponentialMovingAverages
import services.DBService


object PoloniexMarketService {
  trait CandleManagerMessage
  case class GetCandles(marketName: String) extends CandleManagerMessage
  case class GetLastestCandle(marketName: String) extends CandleManagerMessage
  case class SetCandles(marketName: String, candles: List[MarketCandle]) extends CandleManagerMessage
  case object StartCapture extends CandleManagerMessage
  case object EndCapture extends CandleManagerMessage
  case class GetMovingAverage(marketname: String, time: OffsetDateTime) extends CandleManagerMessage
  case class GetMovingAverages(marketName: String) extends CandleManagerMessage
  case class GetVolume(marketName: String, time: OffsetDateTime) extends CandleManagerMessage
  case class GetVolumes(marketName: String) extends CandleManagerMessage
  case class GetLatestMessage(marketName: String) extends CandleManagerMessage
}

/**
  * This actor is responsible for managing candles for all markets.
  */
class PoloniexMarketService @Inject()(val database: DBService,
                                      conf: Configuration) extends Actor
  with ActorLogging
  with ReceivePipeline
  with MarketCandles
  with ExponentialMovingAverages
  with Volume24HourTracking
  with LastMarketMessage
  with Archiving {

  import PoloniexMarketService._
  import PoloniexCandleRetrieverService._

  // This must execute before the interceptors in the other
  // traits
  pipelineOuter {
    // need to catch the update messages first so
    // we can signal if we need to retrieve the candles
    case msg: MarketMessage =>
      val marketName = msg.cryptoCurrency

      // only care about BTC markets
      if (marketName.startsWith("BTC") && !marketCandles.contains(marketName) &&
        msg.baseVolume > baseVolumeRule) {

        // send a message to the retriever to get the candle data from Poloniex
        // if the 24 hour baseVolume from this update is greater than our threshold
        eventBus.publish(MarketEvent(NewMarket, QueueMarket(marketName)))
      }

      Inner(msg)
  }

  val eventBus = PoloniexEventBus()
  val baseVolumeRule = conf.getInt("poloniex.candle.baseVolume").getOrElse(500)
  override val periodMinutes = 5

  override def preStart() = {
    log info "subscribed to market updates"
    eventBus.subscribe(self, Updates)
    eventBus.subscribe(self, Candles)
  }

  override def postStop() = {
    eventBus.unsubscribe(self, Updates)
    eventBus.unsubscribe(self, Candles)
  }

  def receive: Receive = {

    case StartCapture =>
      beginSession(marketCandles.map( m => Can(m._1, m._2.toList)).toList)
    case EndCapture =>
      endSession()

    case GetCandles(marketName) =>
      sender ! getMarketCandles(marketName)

    case GetLastestCandle(marketName) =>
      sender ! getLatestCandle(marketName)

    /**
      * Returns a List[(Int, BigDecimal)] to the sender
      */
    case GetMovingAverage(marketName, time) =>
      sender ! getMovingAverage(marketName, time)

    /**
      * Send to original sender the moving averages of a market.
      * retunrs a List[(Int, List[ExponentialMovingAverage])]
      */
    case GetMovingAverages(marketName) =>
      sender ! getMovingAverages(marketName)

    case GetVolume(marketName, time) =>
      sender ! getVolume(marketName, time)

    case GetVolumes(marketName) =>
      sender ! getVolumes(marketName)

    case GetLatestMessage(marketName) =>
      sender ! getLatestMessage(marketName)
  }
}