/*
rule = "scala:fix.v3_0_0"
 */
package fix

import cats.effect.IO
import cats.effect.Bracket
import cats.effect.Sync

object BracketRewrites {
  Bracket.apply[IO, Throwable]

  Bracket[IO, Throwable].guarantee(IO.unit)(IO.unit)

  Sync[IO].guarantee(IO.unit)(IO.unit)

  def f1[F[_], E](implicit F: Bracket[F, E]): Unit = ()

  private val x1 = Bracket[IO, Throwable]
  x1.guarantee(IO.unit)(IO.unit)
}
