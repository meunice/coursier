package coursier.cli

import java.io.{File, Writer}
import java.util.concurrent._

import ammonite.terminal.{ TTY, Ansi }

import coursier.Files.Logger

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

class TermDisplay(
  out: Writer,
  var fallbackMode: Boolean = false
) extends Logger {

  private val ansi = new Ansi(out)
  private var width = 80
  private val refreshInterval = 1000 / 60
  private val fallbackRefreshInterval = 1000

  private val lock = new AnyRef
  private val t = new Thread("TermDisplay") {
    override def run() = lock.synchronized {

      val baseExtraWidth = width / 5

      def reflowed(url: String, info: Info) = {
        val pctOpt = info.pct.map(100.0 * _)
        val extra =
          if (info.length.isEmpty && info.downloaded == 0L)
            ""
          else
            s"(${pctOpt.map(pct => f"$pct%.2f %%, ").mkString}${info.downloaded}${info.length.map(" / " + _).mkString})"

        val total = url.length + 1 + extra.length
        val (url0, extra0) =
          if (total >= width) { // or > ? If equal, does it go down 2 lines?
          val overflow = total - width + 1

            val extra0 =
              if (extra.length > baseExtraWidth)
                extra.take((baseExtraWidth max (extra.length - overflow)) - 1) + "…"
              else
                extra

            val total0 = url.length + 1 + extra0.length
            val overflow0 = total0 - width + 1

            val url0 =
              if (total0 >= width)
                url.take(((width - baseExtraWidth - 1) max (url.length - overflow0)) - 1) + "…"
              else
                url

            (url0, extra0)
          } else
            (url, extra)

        (url0, extra0)
      }


      @tailrec def helper(lineCount: Int): Unit =
        Option(q.poll(100L, TimeUnit.MILLISECONDS)) match {
          case None => helper(lineCount)
          case Some(Left(())) => // poison pill
          case Some(Right(())) =>
            // update display

            val downloads0 = downloads.synchronized {
              downloads
                .toVector
                .map { url => url -> infos.get(url) }
                .sortBy { case (_, info) => - info.pct.sum }
            }

            for ((url, info) <- downloads0) {
              assert(info != null, s"Incoherent state ($url)")

              val (url0, extra0) = reflowed(url, info)

              ansi.clearLine(2)
              out.write(s"$url0 $extra0\n")
            }

            if (downloads0.length < lineCount) {
              for (_ <- downloads0.length until lineCount) {
                ansi.clearLine(2)
                ansi.down(1)
              }

              for (_ <- downloads0.length until lineCount)
                ansi.up(1)
            }

            for (_ <- downloads0.indices)
              ansi.up(1)

            out.flush()
            Thread.sleep(refreshInterval)
            helper(downloads0.length)
        }


      @tailrec def fallbackHelper(previous: Set[String]): Unit =
        Option(q.poll(100L, TimeUnit.MILLISECONDS)) match {
          case None => fallbackHelper(previous)
          case Some(Left(())) => // poison pill
          case Some(Right(())) =>
            val downloads0 = downloads.synchronized {
              downloads
                .toVector
                .map { url => url -> infos.get(url) }
                .sortBy { case (_, info) => - info.pct.sum }
            }

            var displayedSomething = false
            for ((url, info) <- downloads0 if previous(url)) {
              assert(info != null, s"Incoherent state ($url)")

              val (url0, extra0) = reflowed(url, info)

              displayedSomething = true
              out.write(s"$url0 $extra0\n")
            }

            if (displayedSomething)
              out.write("\n")

            out.flush()
            Thread.sleep(fallbackRefreshInterval)
            fallbackHelper(previous ++ downloads0.map { case (url, _) => url })
          }

      if (fallbackMode)
        fallbackHelper(Set.empty)
      else
        helper(0)
    }
  }

  t.setDaemon(true)

  def init(): Unit = {
    try {
      width = TTY.consoleDim("cols")
      ansi.clearLine(2)
    } catch { case _: Exception =>
      fallbackMode = true
    }

    t.start()
  }

  def stop(): Unit = {
    q.put(Left(()))
    lock.synchronized(())
  }

  private case class Info(downloaded: Long, length: Option[Long]) {
    def pct: Option[Double] = length.map(downloaded.toDouble / _)
  }

  private val downloads = new ArrayBuffer[String]
  private val infos = new ConcurrentHashMap[String, Info]

  private val q = new LinkedBlockingDeque[Either[Unit, Unit]]
  def update(): Unit = {
    if (q.size() == 0)
      q.put(Right(()))
  }

  override def downloadingArtifact(url: String, file: File): Unit = {
    assert(!infos.containsKey(url))
    val prev = infos.putIfAbsent(url, Info(0L, None))
    assert(prev == null)

    if (fallbackMode) {
      // FIXME What about concurrent accesses to out from the thread above?
      out.write(s"Downloading $url\n")
      out.flush()
    }

    downloads.synchronized {
      downloads.append(url)
    }

    update()
  }
  override def downloadLength(url: String, length: Long): Unit = {
    val info = infos.get(url)
    assert(info != null)
    val newInfo = info.copy(length = Some(length))
    infos.put(url, newInfo)

    update()
  }
  override def downloadProgress(url: String, downloaded: Long): Unit = {
    val info = infos.get(url)
    assert(info != null)
    val newInfo = info.copy(downloaded = downloaded)
    infos.put(url, newInfo)

    update()
  }
  override def downloadedArtifact(url: String, success: Boolean): Unit = {
    downloads.synchronized {
      downloads -= url
    }

    if (fallbackMode) {
      // FIXME What about concurrent accesses to out from the thread above?
      out.write(s"Downloaded $url\n")
      out.flush()
    }

    val info = infos.remove(url)
    assert(info != null)

    update()
  }

}
