/*
 * Copyright 2020 Typelevel
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

package cats.effect

import cats._, implicits._
import cats.data.Kleisli
import cats.effect.concurrent.Deferred


import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration._
import scala.util.Success

import cats.laws._
import cats.laws.discipline._
import cats.laws.discipline.arbitrary._
import cats.kernel.laws.discipline.MonoidTests

//import cats.effect.laws.discipline.arbitrary._
import cats.effect.testkit.TestContext
import org.scalacheck.Prop, Prop.forAll
import org.specs2.ScalaCheck
import org.typelevel.discipline.specs2.mutable.Discipline

class ResourceSpec extends BaseSpec with ScalaCheck with Discipline {
  // We need this for testing laws: prop runs can interfere with each other
  sequential

  "Resource[IO, *]" >> {
    "releases resources in reverse order of acquisition" in ticked { implicit ticker =>
      forAll { (as: List[(Int, Either[Throwable, Unit])]) =>
        var released: List[Int] = Nil
        val r = as.traverse {
          case (a, e) =>
            Resource.make(IO(a))(a => IO { released = a :: released } *> IO.fromEither(e))
        }
        r.use(IO.pure).attempt.void must completeAs(())
        released mustEqual as.map(_._1)
      }
    }

    "releases both resources on combine" in ticked { implicit ticker =>
      forAll { (rx: Resource[IO, Int], ry: Resource[IO, Int]) =>
        var acquired: Set[Int] = Set.empty
        var released: Set[Int] = Set.empty
        def observe(r: Resource[IO, Int]) = r.flatMap { a =>
          Resource.make(IO(acquired += a) *> IO.pure(a))(a => IO(released += a)).as(())
        }
        observe(rx).combine(observe(ry)).use(IO.pure).attempt.void must completeAs(())
        released mustEqual acquired
      }
    }

    // TODO does not compile, same as the laws (missing IO instance)
  // "releases both resources on combineK" in ticked { implicit ticker =>
  //   forAll { (rx: Resource[IO, Int], ry: Resource[IO, Int]) =>
  //     var acquired: Set[Int] = Set.empty
  //     var released: Set[Int] = Set.empty
  //     def observe(r: Resource[IO, Int]) = r.flatMap { a =>
  //       Resource.make(IO(acquired += a) *> IO.pure(a))(a => IO(released += a)).as(())
  //     }
  //     observe(rx).combineK(observe(ry)).use(_ => IO.unit).attempt.void must completeAs(())
  //     released mustEqual acquired
  //   }
  // }

 "releases resources that implement AutoCloseable" in ticked { implicit ticker =>
    var closed = false
    val autoCloseable = new AutoCloseable {
      override def close(): Unit = closed = true
    }

    val result = Resource
      .fromAutoCloseable(IO(autoCloseable))
      .use(_ => IO.pure("Hello world"))
      .attempt.void must completeAs(())

    result mustEqual "Hello world"
    closed must beTrue
  }

    // TODO obsolete, just delete this
// "resource from AutoCloseableBlocking is auto closed and executes in the blocking context" in ticked { implicit ticker =>
//     implicit val ctx: ContextShift[IO] = ec.ioContextShift

//     val blockingEc = TestContext()
//     val blocker = Blocker.liftExecutionContext(blockingEc)

//     var closed = false
//     val autoCloseable = new AutoCloseable {
//       override def close(): Unit = closed = true
//     }

//     var acquired = false
//     val acquire = IO {
//       acquired = true
//       autoCloseable
//     }

//     val result = Resource
//       .fromAutoCloseableBlocking(blocker)(acquire)
//       .use(_ => IO.pure("Hello world"))
//       .unsafeToFuture()

//     // Check that acquire ran inside the blocking context.
//     ec.tick()
//     acquired shouldBe false
//     blockingEc.tick()
//     acquired shouldBe true

//     // Check that close was called and ran inside the blocking context.
//     ec.tick()
//     closed shouldBe false
//     blockingEc.tick()
//     closed shouldBe true

//     // Check the final result.
//     ec.tick()
//     result.value shouldBe Some(Success("Hello world"))
//   }

    "liftF" in ticked { implicit ticker =>
      forAll { (fa: IO[String]) =>
        Resource.liftF(fa).use(IO.pure) mustEqual fa
      }
    }

    // "liftF - interruption" in ticked { implicit ticker =>
    //   def p =
    //     Deferred[IO, Outcome[IO, Throwable, Int]]
    //       .flatMap { stop =>
    //         val r = Resource
    //           .liftF(IO.never: IO[Int])
    //           .use(IO.pure)
    //           .guaranteeCase(stop.complete)

    //         r.start.flatMap { fiber =>
    //           IO.sleep(200.millis) >> fiber.cancel >> stop.get
    //         }
    //       }
    //       .timeout(2.seconds)

    //   p must completeAs(Outcome.canceled) // TODO add a beCanceled matcher
    // }

    "liftF(fa) <-> liftK.apply(fa)" in ticked { implicit ticker =>
      forAll { (fa: IO[String], f: String => IO[Int]) =>
        Resource.liftF(fa).use(f) mustEqual Resource.liftK[IO].apply(fa).use(f)
      }
    }

    "evalMap" in ticked { implicit ticker =>
       forAll { (f: Int => IO[Int]) =>
        Resource.liftF(IO(0)).evalMap(f).use(IO.pure) mustEqual f(0)
      }
    }

    "evalMap with error <-> IO.raiseError" in ticked { implicit ticker =>
      case object Foo extends Exception

      forAll { (g: Int => IO[Int]) =>
        val effect: Int => IO[Int] = a => (g(a) <* IO(throw Foo))
        Resource.liftF(IO(0)).evalMap(effect).use(IO.pure) mustEqual IO.raiseError(Foo)
      }
    }

    "evalTap" in ticked { implicit ticker =>
      forAll { (f: Int => IO[Int]) =>
        Resource.liftF(IO(0)).evalTap(f).use(IO.pure) mustEqual f(0).as(0)
      }
    }

    // TODO cancel boundary does not exist, use canceled? or sleep?
    // "evalTap with cancellation <-> IO.never" in ticked { implicit ticker =>
    //   forAll { (g: Int => IO[Int]) =>
    //     val effect: Int => IO[Int] = a =>
    //     for {
    //       f <- (g(a) <* IO.cancelBoundary).start
    //       _ <- f.cancel
    //       r <- f.join
    //     } yield r

    //     Resource.liftF(IO(0)).evalTap(effect).use(IO.pure) mustEqual IO.never
    //   }
    // }

    "evalTap with error <-> IO.raiseError" in ticked { implicit ticker =>
      case object Foo extends Exception

      forAll { (g: Int => IO[Int]) =>
        val effect: Int => IO[Int] = a => (g(a) <* IO(throw Foo))
        Resource.liftF(IO(0)).evalTap(effect).use(IO.pure) mustEqual IO.raiseError(Foo)
      }
    }

    "mapK" in ticked { implicit ticker =>
      forAll { (fa: Kleisli[IO, Int, Int]) =>
        val runWithTwo = new ~>[Kleisli[IO, Int, *], IO] {
          override def apply[A](fa: Kleisli[IO, Int, A]): IO[A] = fa(2)
        }
        Resource.liftF(fa).mapK(runWithTwo).use(IO.pure) mustEqual fa(2)
      }
    }

  // TODO does not compile, missing instance for Kleisli ?
  // "mapK should preserve ExitCode-specific behaviour" in ticked { implicit ticker =>
  //   val takeAnInteger = new ~>[IO, Kleisli[IO, Int, *]] {
  //     override def apply[A](fa: IO[A]): Kleisli[IO, Int, A] = Kleisli.liftF(fa)
  //   }

  //   def sideEffectyResource: (AtomicBoolean, Resource[IO, Unit]) = {
  //     val cleanExit = new java.util.concurrent.atomic.AtomicBoolean(false)
  //     val res = Resource.makeCase(IO.unit) {
  //       case (_, Resource.ExitCase.Completed) =>
  //         IO {
  //           cleanExit.set(true)
  //         }
  //       case _ => IO.unit
  //     }
  //     (cleanExit, res)
  //   }

  //   val (clean, res) = sideEffectyResource
  //   res.use(_ => IO.unit).attempt.void must completeAs(())
  //   clean.get() must beFalse

  //   val (clean1, res1) = sideEffectyResource
  //   res1.use(_ => IO.raiseError(new Throwable("oh no"))).attempt.void must completeAs(())
  //   clean1.get() must beFalse

  //   val (clean2, res2) = sideEffectyResource
  //   res2
  //     .mapK(takeAnInteger)
  //     .use(_ => Kleisli.liftF(IO.raiseError[Unit](new Throwable("oh no"))))
  //     .run(0)
  //     .attempt
  //     .void must completeAs(())
  //   clean2.get() must beFalse
  // }

  "allocated produces the same value as the resource" in ticked { implicit ticker =>
    forAll { (resource: Resource[IO, Int]) =>
      val a0 = Resource(resource.allocated).use(IO.pure).attempt
      val a1 = resource.use(IO.pure).attempt

      a0 mustEqual a1
    }
  }

 "allocate does not release until close is invoked" in ticked { implicit ticker =>
    val released = new java.util.concurrent.atomic.AtomicBoolean(false)
    val release = Resource.make(IO.unit)(_ => IO(released.set(true)))
    val resource = Resource.liftF(IO.unit)

    val prog = for {
      res <- (release *> resource).allocated
      (_, close) = res
      _ <- IO(released.get() must beFalse)
      _ <- close
      _ <- IO(released.get() must beFalse)
    } yield ()

    prog must completeAs(())
  }

  "allocate does not release until close is invoked on mapK'd Resources" in ticked { implicit ticker =>
    val released = new java.util.concurrent.atomic.AtomicBoolean(false)

    val runWithTwo = new ~>[Kleisli[IO, Int, *], IO] {
      override def apply[A](fa: Kleisli[IO, Int, A]): IO[A] = fa(2)
    }
    val takeAnInteger = new ~>[IO, Kleisli[IO, Int, *]] {
      override def apply[A](fa: IO[A]): Kleisli[IO, Int, A] = Kleisli.liftF(fa)
    }
    val plusOne = Kleisli { (i: Int) =>
      IO(i + 1)
    }
    val plusOneResource = Resource.liftF(plusOne)

    val release = Resource.make(IO.unit)(_ => IO(released.set(true)))
    val resource = Resource.liftF(IO.unit)

    val prog = for {
      res <- ((release *> resource).mapK(takeAnInteger) *> plusOneResource).mapK(runWithTwo).allocated
      (_, close) = res
      _ <- IO(released.get() must beFalse)
      _ <- close
      _ <- IO(released.get() must beFalse)
    } yield ()

    prog must completeAs(())
  }

  "safe attempt suspended resource" in ticked { implicit ticker =>
    val exception = new Exception("boom!")
    val suspend = Resource.suspend[IO, Unit](IO.raiseError(exception))
    suspend.use(IO.pure) must failAs(exception)
  }

  "parZip - releases resources in reverse order of acquisition" in ticked { implicit ticker =>

    // conceptually asserts that:
    //   forAll (r: Resource[F, A]) then r <-> r.parZip(Resource.unit) <-> Resource.unit.parZip(r)
    // needs to be tested manually to assert the equivalence during cleanup as well
    forAll { (as: List[(Int, Either[Throwable, Unit])], rhs: Boolean) =>
      var released: List[Int] = Nil
      val r = as.traverse {
        case (a, e) =>
          Resource.make(IO(a))(a => IO { released = a :: released } *> IO.fromEither(e))
      }
      val unit = ().pure[Resource[IO, *]]
      val p = if (rhs) r.parZip(unit) else unit.parZip(r)

      p.use(IO.pure).attempt.void must completeAs(())
      released mustEqual as.map(_._1)
    }
  }

  "parZip - parallel acquisition and release" in ticked { implicit ticker =>
    var leftAllocated = false
    var rightAllocated = false
    var leftReleasing = false
    var rightReleasing = false
    var leftReleased = false
    var rightReleased = false

    val wait = IO.sleep(1.second)
    val lhs = Resource.make(wait >> IO { leftAllocated = true }) { _ =>
      IO { leftReleasing = true } >> wait >> IO { leftReleased = true }
    }
    val rhs = Resource.make(wait >> IO { rightAllocated = true }) { _ =>
      IO { rightReleasing = true } >> wait >> IO { rightReleased = true }
    }

    (lhs, rhs).parTupled.use(_ => wait).unsafeToFuture()

    // after 1 second:
    //  both resources have allocated (concurrency, serially it would happen after 2 seconds)
    //  resources are still open during `use` (correctness)
    ticker.ctx.tick(1.second)
    leftAllocated must beTrue
    rightAllocated must beTrue
    leftReleasing must beFalse
    rightReleasing must beFalse

    // after 2 seconds:
    //  both resources have started cleanup (correctness)
    ticker.ctx.tick(1.second)
    leftReleasing must beTrue
    rightReleasing must beTrue
    leftReleased must beFalse
    rightReleased must beFalse

    // after 3 seconds:
    //  both resources have terminated cleanup (concurrency, serially it would happen after 4 seconds)
    ticker.ctx.tick(1.second)
    leftReleased must beTrue
    rightReleased must beTrue
  }

  "parZip - safety: lhs error during rhs interruptible region" in ticked { implicit ticker =>

    var leftAllocated = false
    var rightAllocated = false
    var leftReleasing = false
    var rightReleasing = false
    var leftReleased = false
    var rightReleased = false

    def wait(n: Int) = IO.sleep(n.seconds)
    val lhs = for {
      _ <- Resource.make(wait(1) >> IO { leftAllocated = true }) { _ =>
        IO { leftReleasing = true } >> wait(1) >> IO { leftReleased = true }
      }
      _ <- Resource.liftF(wait(1) >> IO.raiseError[Unit](new Exception))
    } yield ()

    val rhs = for {
      _ <- Resource.make(wait(1) >> IO { rightAllocated = true }) { _ =>
        IO { rightReleasing = true } >> wait(1) >> IO { rightReleased = true }
      }
      _ <- Resource.liftF(wait(2))
    } yield ()

    (lhs, rhs).parTupled
      .use(_ => IO.unit)
      .handleError(_ => ())
      .unsafeToFuture()

    // after 1 second:
    //  both resources have allocated (concurrency, serially it would happen after 2 seconds)
    //  resources are still open during `flatMap` (correctness)
    ticker.ctx.tick(1.second)
    leftAllocated must beTrue
    rightAllocated must beTrue
    leftReleasing must beFalse
    rightReleasing must beFalse

    // after 2 seconds:
    //  both resources have started cleanup (interruption, or rhs would start releasing after 3 seconds)
    ticker.ctx.tick(1.second)
    leftReleasing must beTrue
    rightReleasing must beTrue
    leftReleased must beFalse
    rightReleased must beFalse

    // after 3 seconds:
    //  both resources have terminated cleanup (concurrency, serially it would happen after 4 seconds)
    ticker.ctx.tick(1.second)
    leftReleased must beTrue
    rightReleased must beTrue
  }

  "parZip - safety: rhs error during lhs uninterruptible region" in ticked { implicit ticker =>

    var leftAllocated = false
    var rightAllocated = false
    var rightErrored = false
    var leftReleasing = false
    var rightReleasing = false
    var leftReleased = false
    var rightReleased = false

    def wait(n: Int) = IO.sleep(n.seconds)
    val lhs = Resource.make(wait(3) >> IO { leftAllocated = true }) { _ =>
      IO { leftReleasing = true } >> wait(1) >> IO { leftReleased = true }
    }
    val rhs = for {
      _ <- Resource.make(wait(1) >> IO { rightAllocated = true }) { _ =>
        IO { rightReleasing = true } >> wait(1) >> IO { rightReleased = true }
      }
      _ <- Resource.make(wait(1) >> IO { rightErrored = true } >> IO.raiseError[Unit](new Exception))(_ => IO.unit)
    } yield ()

    (lhs, rhs).parTupled
      .use(_ => wait(1))
      .handleError(_ => ())
      .unsafeToFuture()

    // after 1 second:
    //  rhs has partially allocated, lhs executing
    ticker.ctx.tick(1.second)
    leftAllocated must beFalse
    rightAllocated must beTrue
    rightErrored must beFalse
    leftReleasing must beFalse
    rightReleasing must beFalse

    // after 2 seconds:
    //  rhs has failed, release blocked since lhs is in uninterruptible allocation
    ticker.ctx.tick(1.second)
    leftAllocated must beFalse
    rightAllocated must beTrue
    rightErrored must beTrue
    leftReleasing must beFalse
    rightReleasing must beFalse

    // after 3 seconds:
    //  lhs completes allocation (concurrency, serially it would happen after 4 seconds)
    //  both resources have started cleanup (correctness, error propagates to both sides)
    ticker.ctx.tick(1.second)
    leftAllocated must beTrue
    leftReleasing must beTrue
    rightReleasing must beTrue
    leftReleased must beFalse
    rightReleased must beFalse

    // after 4 seconds:
    //  both resource have terminated cleanup (concurrency, serially it would happen after 5 seconds)
    ticker.ctx.tick(1.second)
    leftReleased must beTrue
    rightReleased must beTrue
  }
 }


  {
    implicit val ticker = Ticker(TestContext())

    checkAll(
      "Resource[IO, *]",
      MonadErrorTests[Resource[IO, *], Throwable].monadError[Int, Int, Int]
    )
  }

  {
    implicit val ticker = Ticker(TestContext())

    checkAll(
      "Resource[IO, Int]",
      MonoidTests[Resource[IO, Int]].monoid
    )
  }

  // TODO We're missing a semigroupK instance for IO
  // {
  //   implicit val ticker = Ticker(TestContext())

  //   checkAll(
  //     "Resource[IO, *]",
  //     SemigroupKTests[Resource[IO, *]].semigroupK[Int]
  //   )
  // }

  // TODO Investigate failure
  // {
  //   implicit val ticker = Ticker(TestContext())

  //   checkAll(
  //     "Resource.Par[IO, *]",
  //     CommutativeApplicativeTests[Resource.Par[IO, *]].commutativeApplicative[Int, Int, Int]
  //   )
  // }

  {
    implicit val ticker = Ticker(TestContext())

    // do NOT inline this val; it causes the 2.13.0 compiler to crash for... reasons (see: scala/bug#11732)
    val module: ParallelTests.Aux[Resource[IO, *], Resource.Par[IO, *]] =
      ParallelTests[Resource[IO, *]]

    checkAll(
      "Resource[IO, *]",
      module.parallel[Int, Int]
    )
  }
}