package scala.actors.io

import java.nio.channels._
import java.nio.ByteBuffer
import scala.actors.controlflow._
import scala.actors.controlflow.AsyncStreamBuilder
import scala.actors.controlflow.ControlFlow._
import scala.actors.controlflow.concurrent.AsyncLock
import scala.binary.Binary
import scala.collection.immutable.Queue
import scala.collection.jcl.Conversions._

trait RichWritableByteChannel {
  val channel: SelectableChannel with GatheringByteChannel
  val richSelector: RichSelector
  
  private val writeLock = new AsyncLock()

  private def internalWrite(binary: Binary)(fc: FC[Unit]): Nothing = {
    import fc.implicitThr
    def tryWrite(buffers: Array[ByteBuffer], offset: Int): Nothing = try {
      if (offset >= buffers.length) {
        fc.ret(())
      } else if (!buffers(offset).hasRemaining) {
        // Clean out empty or already-processed buffers.
        buffers(offset) = null // Allow garbage collection.
        tryWrite(buffers, offset + 1) // Avoid re-processing.
      } else {
        //println("Writing buffers: " + buffers.length)
        channel.write(buffers, offset, buffers.length) match {
          case 0 => {
            // Write failed, use selector to callback when ready.
            richSelector.register(channel, RichSelector.Write) { () => tryWrite(buffers, offset) }
            Actor.exit
          }
          case _ => {
            //println("RichWritableByteChannel: wrote "+length+" bytes.")
            tryWrite(buffers, offset)
          }
        }
      }
    } catch {
      case e: Exception => fc.thr(e)
    }
    tryWrite(binary.byteBuffers.toList.toArray, 0)
  }

  def asyncWrite(binary: Binary)(fc: FC[Unit]): Nothing = {
    writeLock.syn(internalWrite(binary)(_: FC[Unit]))(fc)
  }

  private def asyncWrite(as: AsyncStream[Binary])(fc: FC[Unit]): Nothing = {
    (writeLock.syn { (fc2: FC[Unit]) =>
      as.asyncForeach(internalWrite(_: Binary)(_: FC[Unit]))(fc2)
    })(fc)
  }
}
