package coop.rchain.rholang
import java.io.File
import java.nio.file.{Files, Path}

import cats.effect.{Resource, Sync}
import cats.Applicative
import cats.effect.ExitCase.Error
import com.typesafe.scalalogging.Logger
import coop.rchain.models._
import coop.rchain.rholang.interpreter.Runtime.{RhoContext, RhoISpace}
import coop.rchain.rholang.interpreter.errors.OutOfPhlogistonsError
import coop.rchain.rspace.history.Branch
import coop.rchain.rspace.{Context, RSpace}

import scala.reflect.io.Directory

object Resources {
  val logger: Logger = Logger(this.getClass.getName.stripSuffix("$"))

  def mkTempDir[F[_]: Applicative](prefix: String): Resource[F, Path] =
    Resource.makeCase(Applicative[F].pure(Files.createTempDirectory(prefix)))(
      (path, exitCase) =>
        Applicative[F].pure(exitCase match {
          case Error(ex) =>
            logger
              .error(
                s"Exception thrown while using the tempDir '$path'. Temporary dir NOT deleted.",
                ex
              )
          case _ => new Directory(new File(path.toString)).deleteRecursively()
        })
    )

  def mkRhoISpace[F[_]: Sync, A](
      prefix: String = "",
      branch: String = "test",
      mapSize: Long = 1024L * 1024L * 4
  ): Resource[F, RhoISpace[F]] = {
    import coop.rchain.rholang.interpreter.storage.implicits._

    def mkRspace(dbDir: Path): F[RhoISpace[F]] = {
      val context: RhoContext = Context.create(dbDir, mapSize)

      RSpace.create[
        F,
        Par,
        BindPattern,
        OutOfPhlogistonsError.type,
        ListParWithRandom,
        ListParWithRandomAndPhlos,
        TaggedContinuation
      ](context, Branch(branch))
    }

    mkTempDir(prefix)
      .flatMap(tmpDir => Resource.make(mkRspace(tmpDir))(_.close()))
  }
}