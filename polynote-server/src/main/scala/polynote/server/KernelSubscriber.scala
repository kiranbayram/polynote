package polynote.server

import java.util.concurrent.atomic.AtomicInteger

import fs2.concurrent.Topic
import fs2.Stream
import polynote.kernel.environment.{Config, PublishMessage}
import polynote.kernel.{BaseEnv, GlobalEnv}
import polynote.messages.{CellID, KernelStatus, Notebook, NotebookUpdate}
import KernelPublisher.{GlobalVersion, SubscriberId}
import polynote.server.auth.{IdentityProvider, Permission, UserIdentity}
import zio.{Fiber, Promise, RIO, Task, UIO, ZIO}
import zio.interop.catz._


class KernelSubscriber private[server] (
  id: SubscriberId,
  val closed: Promise[Throwable, Unit],
  process: Fiber[Throwable, Unit],
  val publisher: KernelPublisher,
  val lastLocalVersion: AtomicInteger,
  val lastGlobalVersion: AtomicInteger
) {

  def close(): UIO[Unit] = closed.succeed(()).unit *> process.interrupt.unit
  def update(update: NotebookUpdate): Task[Unit] = publisher.update(id, update) *> ZIO(lastLocalVersion.set(update.localVersion)) *> ZIO(lastGlobalVersion.set(update.globalVersion))
  def notebook: Task[Notebook] = publisher.latestVersion.map(_._2)
  def currentPath: Task[String] = notebook.map(_.path)
  def checkPermission(permission: String => Permission): ZIO[SessionEnv, Throwable, Unit] =
    currentPath.map(permission) >>= IdentityProvider.checkPermission
}

object KernelSubscriber {

  def apply(
    id: SubscriberId,
    publisher: KernelPublisher
  ): RIO[PublishMessage, KernelSubscriber] = {

    def rebaseUpdate(update: NotebookUpdate, globalVersion: GlobalVersion, localVersion: Int) =
      publisher.versionBuffer.getRange(update.globalVersion, globalVersion)
        .foldLeft(update)(_ rebase _)
        .withVersions(globalVersion, localVersion)

    def foreignUpdates(local: AtomicInteger, global: AtomicInteger) =
      publisher.broadcastUpdates.subscribe(128).unNone.filter(_._1 != id).map(_._2).map {
        update =>
          val knownGlobalVersion = global.get()
          if (update.globalVersion < knownGlobalVersion) {
            Some(rebaseUpdate(update, knownGlobalVersion, local.get()))
          } else if (update.globalVersion > knownGlobalVersion) {
            Some(update.withVersions(update.globalVersion, local.get()))
          } else None
      }.unNone.evalTap(_ => ZIO(local.incrementAndGet()).unit)

    for {
      closed           <- Promise.make[Throwable, Unit]
      versioned        <- publisher.versionedNotebook.get
      (ver, notebook)   = versioned
      lastLocalVersion  = new AtomicInteger(0)
      lastGlobalVersion = new AtomicInteger(ver)
      publishMessage   <- PublishMessage.access
      updater          <- Stream.emits(Seq(
          foreignUpdates(lastLocalVersion, lastGlobalVersion),
          publisher.status.subscribe(128).tail.map(KernelStatus(_)),
          publisher.cellResults.subscribe(128).tail.unNone
        )).parJoinUnbounded.interruptWhen(closed.await.either).through(publishMessage.publish).compile.drain.fork
    } yield new KernelSubscriber(
      id,
      closed,
      updater,
      publisher,
      lastLocalVersion,
      lastGlobalVersion
    )
  }

}