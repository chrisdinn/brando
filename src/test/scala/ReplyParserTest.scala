package brando

import org.scalatest.FunSpec
import akka.util.ByteString

class ReplyParserTest extends FunSpec {

  object Parser extends ReplyParser
  import Parser._

  describe("Status reply") {
    it("should decode Ok") {
      val result = parse(ByteString("+OK\r\n"))

      assert(result === Success(Some(Ok)))
    }

    it("should decode Pong") {
      val result = parse(ByteString("+PONG\r\n"))

      assert(result === Success(Some(Pong)))
    }
  }

  describe("Integer reply") {
    it("should decode as long") {
      parse(ByteString(":17575\r\n")) match {
        case Success(Some(i: Long), next) ⇒ assert(i == 17575L)
        case _                            ⇒ assert(false)
      }
    }
  }

  describe("Bulk reply") {
    it("should decode as ByteString option") {
      val result = parse(ByteString("$6\r\nfoobar\r\n"))

      assert(result === Success(Some(ByteString("foobar"))))
    }

    it("should decode null as None") {
      val result = parse(ByteString("$-1\r\n"))

      assert(result === Success(None))
    }
  }

  describe("Multi Bulk reply") {
    it("should decode list of bulk reply values") {
      val result = parse(ByteString("*4\r\n$3\r\nfoo\r\n$3\r\nbar\r\n$4\r\nfoob\r\n$6\r\nfoobar\r\n"))

      val expected = Some(List(Some(ByteString("foo")), Some(ByteString("bar")),
        Some(ByteString("foob")), Some(ByteString("foobar"))))

      assert(result === Success(expected))
    }

    it("should decode list of with nil values") {
      val result = parse(ByteString("*3\r\n$-1\r\n$3\r\nbar\r\n$6\r\nfoobar\r\n"))

      val expected = Some(List(None, Some(ByteString("bar")),
        Some(ByteString("foobar"))))

      assert(result === Success(expected))
    }

    it("should decode list of with integer values") {
      val result = parse(ByteString("*3\r\n$3\r\nbar\r\n:37282\r\n$6\r\nfoobar\r\n"))

      val expected = Some(List(Some(ByteString("bar")), Some(37282),
        Some(ByteString("foobar"))))

      assert(result === Success(expected))
    }

    it("should decode list of with nested multi bulk reply") {
      val result = parse(ByteString("*3\r\n$3\r\nbar\r\n*4\r\n$3\r\nfoo\r\n$3\r\nbar\r\n$4\r\nfoob\r\n$6\r\nfoobar\r\n$6\r\nfoobaz\r\n"))

      val expected = Some(List(Some(ByteString("bar")),
        Some(List(Some(ByteString("foo")), Some(ByteString("bar")),
          Some(ByteString("foob")), Some(ByteString("foobar")))),
        Some(ByteString("foobaz"))))

      assert(result === Success(expected))
    }
  }

  describe("parsing empty replies") {
    it("should return a failure if remaining partial response is empty") {
      val result = parse(ByteString())

      assert(result === Failure(ByteString()))
    }
  }
}
