/*
 * Copyright 2020 Precog Data
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.plugin.mariadb

import scala.{text => _, Stream => _, _}, Predef._
import scala.concurrent.ExecutionContext
import scala.util.Random

import java.util.concurrent.Executors

import cats.effect.{Blocker, IO, Resource}
import cats.effect.testing.specs2.CatsIO

import doobie._
import doobie.implicits._

import org.specs2.execute.{AsResult, Result}
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll

import quasar.api.resource._
import quasar.connector.{MonadResourceErr, ResourceError}
import quasar.contrib.scalaz.MonadError_

trait TestHarness extends Specification with CatsIO with BeforeAll {
  implicit val ioMonadResourceErr: MonadResourceErr[IO] =
    MonadError_.facet[IO](ResourceError.throwableP)

  object Vendors {
    val MariaDB = "MariaDB"
    val MySQL = "MySQL"
    val MemSQL = "MemSQL"
  }

  val TestDb: String = "precog_test"

  val frag = Fragment.const0(_, None)

  def TestUrl(db: Option[String]): String =
    s"jdbc:mariadb://127.0.0.1:33306/${db getOrElse ""}?user=root&allowLocalInfile=true"

  def TestXa(jdbcUrl: String): Resource[IO, Transactor[IO]] =
    Resource.make(IO(Executors.newSingleThreadExecutor()))(p => IO(p.shutdown)) map { ex =>
      Transactor.fromDriverManager(
        "org.mariadb.jdbc.Driver",
        jdbcUrl,
        Blocker.liftExecutionContext(ExecutionContext.fromExecutorService(ex)))
    }

  def beforeAll(): Unit = {
    TestXa(TestUrl(None))
      .use(frag(s"CREATE DATABASE IF NOT EXISTS $TestDb").update.run.transact(_))
      .void
      .unsafeRunSync
  }

  def table(xa: Transactor[IO]): Resource[IO, (ResourcePath, String)] =
    Resource.make(
      IO(s"dest_spec_${Random.alphanumeric.take(6).mkString}"))(
      name => frag(s"DROP TABLE IF EXISTS $name").update.run.transact(xa).void)
      .map(n => (ResourcePath.root() / ResourceName(n), n))

  def tableHarness(jdbcUrl: String = TestUrl(Some(TestDb)))
      : Resource[IO, (Transactor[IO], ResourcePath, String)] =
    for {
      xa <- TestXa(jdbcUrl)
      (path, name) <- table(xa)
    } yield (xa, path, name)

  val vendorName: ConnectionIO[String] =
    HC.getMetaData(FDMD.getDatabaseProductName)

  def onlyVendors[A](xa: Transactor[IO], vs: String*)(a: => IO[A])(implicit A: AsResult[A]): IO[Result] =
    vendorName.transact(xa) flatMap { name =>
      if (vs.contains(name))
        a.map(A.asResult(_))
      else
        IO.pure[Result](skipped("Only supported by: " + vs.mkString(", ")))
    }

  def skipVendors[A](xa: Transactor[IO], vs: String*)(a: => IO[A])(implicit A: AsResult[A]): IO[Result] =
    vendorName.transact(xa) flatMap { name =>
      if (vs.contains(name))
        IO.pure[Result](skipped("Unsupported"))
      else
        a.map(A.asResult(_))
    }
}
