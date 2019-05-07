package com.glossy

import akka.actor.{ Actor, ActorLogging, ActorRef, ActorSystem, Props }
import akka.event.{ Logging }

// GlossyActor companion object
object GlossyActor {

    private val ANTENNA_DELAY: Long = 1000
    private val PROP_DELAY:    Long = 2000
    private val MAX_RELAY_CNT: Int = 5

    def props(): Props = Props(new GlossyActor())
    final case class Meeting(who: ActorRef)
    final case class Send(what: Any)
    final case class GlossyStart(payload: Any)
    final case class GlossyMessage(val relayCnt: Int,
        val payload: Any) {}
}

class GlossyActor extends Actor {
    val log = Logging(context.system, this)

    import GlossyActor._
    // Glossy context
    var startTxTime  : Long = 0
    var startRxTime  : Long = 0
    var relayCntLastTx : Int = 0
    var relayCntLastRx : Int = 0
    var tRefUpdate   : Boolean = false
    var tRefRelayCnt : Int = 0
    var tRefTime     : Long = 0
    var nTx          : Int  = 0
    var nRx          : Int  = 0

    var currentRelayCnt : Int  = 0
    var slotEstimate    : Long = 0
    var actors : List[ActorRef] = List()

    private def start(payload : Any) {
        log.info("GLOSSY_START")
        startTxTime = 0
        startRxTime = 0
        relayCntLastTx = 0
        relayCntLastRx = 0
        tRefUpdate = false
        tRefRelayCnt = 0
        tRefTime = 0
        currentRelayCnt = 0
        slotEstimate = 0
        nTx = 0
        nRx = 0
        val pkt = GlossyMessage(0, payload)
        startTx(pkt)
    }

    private def glossyDebug(): String = {
        val str = f"""
        startTxTime = $startTxTime
        startRxTime = $startRxTime
        relayCntLastTx = $relayCntLastTx
        relayCntLastRx = $relayCntLastRx
        tRefUpdate = $tRefUpdate
        tRefRelayCnt = $tRefRelayCnt
        tRefTime = $tRefTime
        currentRelayCnt = $currentRelayCnt
        slotEstimate = $slotEstimate
        nTx = $nTx
        nRx = $nRx
        """
        return str
    }

    private def startTx(pkt: GlossyMessage) {
        this.startTxTime = System.currentTimeMillis()
        Thread.sleep(GlossyActor.ANTENNA_DELAY)
        this.endTx(pkt)
    }

    private def endTx(pkt: GlossyMessage) {
        log.info("EndTX")
        this.relayCntLastTx = pkt.relayCnt
        if (!this.tRefUpdate) {
            this.tRefTime = this.startTxTime
            this.tRefRelayCnt = pkt.relayCnt
            this.tRefUpdate = true
        }
        if (this.relayCntLastTx == this.relayCntLastRx + 1
            && this.nRx > 0) {
            this.slotEstimate = this.startTxTime - this.startRxTime
            log.info("slot_estimate: " +
                slotEstimate)
        }
        this.nTx += 1
        Thread.sleep(GlossyActor.PROP_DELAY)
        actors.foreach(actor => actor ! pkt)
    }

    private def startRx(relayCnt: Int, payload: Any) {
        this.startRxTime = System.currentTimeMillis()
        Thread.sleep(GlossyActor.ANTENNA_DELAY)
        endRx(relayCnt, payload)
    }
    private def endRx(relayCnt: Int, payload: Any) {
        log.info("EndRX")
        //log.info(glossyDebug())
        val pktRelayCnt: Int = relayCnt
        val relayPkt: GlossyMessage = GlossyMessage(relayCnt + 1, payload)
        this.nRx += 1

        this.relayCntLastRx = pktRelayCnt
        if (!this.tRefUpdate) {
            this.tRefTime = this.startRxTime
            this.tRefRelayCnt = pktRelayCnt
            this.tRefUpdate = true
        }

        if (this.relayCntLastRx == this.relayCntLastTx + 1 &&
            this.nTx > 0) {
            this.slotEstimate = this.startRxTime - this.startTxTime
            log.info("slot_estimate: " +
                slotEstimate)
        }

        /* Note: the original implementiation
         * stops glossy based on the number of tx, not based
         * on the relay counter value
         */
        // if (this.nTx > GlossyActor.MAX_RELAY_CNT) {
        if (relayPkt.relayCnt > GlossyActor.MAX_RELAY_CNT) {
            // stop glossy
            log.info("GLOSSY_STOP")
            return
        } else {
            startTx(relayPkt)
        }
    }

    def receive = {
        case GlossyStart(payload: Any)
            => start(payload)
        case GlossyMessage(relayCnt: Int, payload: Any)
            => startRx(relayCnt, payload)
        case Meeting(who: ActorRef) => { actors = who :: actors }
    }
}


//#main-class
object AkkaQuickstart extends App {
  import GlossyActor._

  val system: ActorSystem = ActorSystem("glossy_sym")

  //#create-actors
  val device1: ActorRef =
    system.actorOf(GlossyActor.props(), "device1")
  val device2: ActorRef =
    system.actorOf(GlossyActor.props(), "device2")
  //#create-actors

  // rendezvous messages
  // Why these?: because I don't know how to broadcast in Akka!
  device1 ! Meeting(device2)
  device2 ! Meeting(device1)

  // glossy sample
  val payload : String = "DONTPANIC"
  device1 ! GlossyStart(payload)
  //#main-send-messages
}
