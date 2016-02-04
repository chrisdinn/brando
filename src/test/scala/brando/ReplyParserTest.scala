package brando

import akka.util.ByteString
import org.scalatest.{BeforeAndAfterEach, FunSpec}

class ReplyParserTest extends FunSpec with BeforeAndAfterEach {

  object Parser extends ReplyParser
  import Parser._

  override def afterEach() {
    remainingBuffer = ByteString.empty
  }

  describe("Simple String reply") {
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

  describe("Error reply") {
    it("should decode the error") {
      val result = parse(ByteString("-err\r\n"))
      result match {
        case Success(Some(akka.actor.Status.Failure(e)), _) ⇒
          assert(e.getMessage === "err")
        case x ⇒ fail(s"Parsed unexpected message $x")
      }
    }
  }

  describe("Bulk String reply") {
    it("should decode as ByteString option") {
      val result = parse(ByteString("$6\r\nfoobar\r\n"))

      assert(result === Success(Some(ByteString("foobar"))))
    }

    it("should decode null as None") {
      val result = parse(ByteString("$-1\r\n"))

      assert(result === Success(None))
    }
  }

  describe("Array reply") {
    it("should decode list of bulk string reply values") {
      val result = parse(ByteString("*4\r\n$3\r\nfoo\r\n$3\r\nbar\r\n$4\r\nfoob\r\n$6\r\nfoobar\r\n"))

      val expected = Some(List(Some(ByteString("foo")), Some(ByteString("bar")),
        Some(ByteString("foob")), Some(ByteString("foobar"))))

      assert(result === Success(expected))
    }

    it("should decode list with nil values") {
      val result = parse(ByteString("*3\r\n$-1\r\n$3\r\nbar\r\n$6\r\nfoobar\r\n"))

      val expected = Some(List(None, Some(ByteString("bar")),
        Some(ByteString("foobar"))))

      assert(result === Success(expected))
    }

    it("should decode list with integer values") {
      val result = parse(ByteString("*3\r\n$3\r\nbar\r\n:37282\r\n$6\r\nfoobar\r\n"))

      val expected = Some(List(Some(ByteString("bar")), Some(37282),
        Some(ByteString("foobar"))))

      assert(result === Success(expected))
    }

    it("should decode list with nested array reply") {
      val result = parse(ByteString("*3\r\n$3\r\nbar\r\n*4\r\n$3\r\nfoo\r\n$3\r\nbar\r\n$4\r\nfoob\r\n$6\r\nfoobar\r\n$6\r\nfoobaz\r\n"))

      val expected = Some(List(Some(ByteString("bar")),
        Some(List(Some(ByteString("foo")), Some(ByteString("bar")),
          Some(ByteString("foob")), Some(ByteString("foobar")))),
        Some(ByteString("foobaz"))))

      assert(result === Success(expected))
    }

    it("should decode null list") {
      assert(parse(ByteString("*-1\r\n")) === Success(None))
    }
  }

  describe("parsing empty replies") {
    it("should return a failure if remaining partial response is empty") {
      parseReply(ByteString()) { r ⇒ fail("nothing to parse") }
    }
  }

  describe("parsing incomplete replies") {

    var parsed = false
    it("should handle an array reply split into two parts") {
      parseReply(ByteString("*")) { _ ⇒ fail("nothing to parse yet") }

      parseReply(ByteString("1\r\n$3\r\nfoo\r\n")) { result ⇒
        val expected = Some(List(Some(ByteString("foo"))))
        assert(result === expected)
        parsed = true
      }
      if (!parsed) fail("Did not parse anything")
    }

    it("should handle an array reply split before an entry") {
      parseReply(ByteString("*1\r\n")) { _ ⇒ fail("nothing to parse yet") }

      var parsed = false
      parseReply(ByteString("$3\r\nfoo\r\n")) { result ⇒
        val expected = Some(List(Some(ByteString("foo"))))
        assert(result === expected)
        parsed = true
      }
      if (!parsed) fail("Did not parse anything")
    }

    it("should handle an array reply split in an entry") {
      parseReply(ByteString("*1\r\n$3\r\n")) { _ ⇒ fail("nothing to parse yet") }

      var parsed = false
      parseReply(ByteString("foo\r\n")) { result ⇒
        val expected = Some(List(Some(ByteString("foo"))))
        assert(result === expected)
        parsed = true
      }
      if (!parsed) fail("Did not parse anything")
    }

    it("should handle an array reply split between entries") {
      parseReply(ByteString("*2\r\n$3\r\nfoo\r\n")) { _ ⇒ fail("nothing to parse yet") }

      var parsed = false
      parseReply(ByteString("$3\r\nbar\r\n")) { result ⇒
        val expected = Some(List(Some(ByteString("foo")), Some(ByteString("bar"))))
        assert(result === expected)
        parsed = true
      }
      if (!parsed) fail("Did not parse anything")
      assert(remainingBuffer === ByteString.empty)
    }

    it("should handle a string reply split into two parts") {
      parseReply(ByteString("+")) { _ ⇒ fail("nothing to parse yet") }

      var parsed = false
      parseReply(ByteString("OK\r\n")) { result ⇒
        val expected = Some(Ok)
        assert(result === expected)
        parsed = true
      }
      if (!parsed) fail("Did not parse anything")
      assert(remainingBuffer === ByteString.empty)
    }

    it("should handle a string reply split after the \\r") {
      parseReply(ByteString("+OK\r")) { _ ⇒ fail("nothing to parse yet") }

      var parsed = false
      parseReply(ByteString("\n")) { result ⇒
        val expected = Some(Ok)
        assert(result === expected)
        parsed = true
      }
      if (!parsed) fail("Did not parse anything")
      assert(remainingBuffer === ByteString.empty)
    }

    it("should handle an integer reply split into two parts") {
      parseReply(ByteString(":")) { _ ⇒ fail("nothing to parse yet") }

      var parsed = false
      parseReply(ByteString("17575\r\n")) { result ⇒
        val expected = Some(17575)
        assert(result === expected)
        parsed = true
      }
      if (!parsed) fail("Did not parse anything")
      assert(remainingBuffer === ByteString.empty)
    }

    it("should handle a bulk string reply split into two parts") {
      parseReply(ByteString("$")) { _ ⇒ fail("nothing to parse yet") }

      var parsed = false
      parseReply(ByteString("3\r\nfoo\r\n")) { result ⇒
        val expected = Some(ByteString("foo"))
        assert(result === expected)
        parsed = true
      }
      if (!parsed) fail("Did not parse anything")
      assert(remainingBuffer === ByteString.empty)
    }

    it("should handle a bulk string reply split after the number") {
      parseReply(ByteString("$3")) { _ ⇒ fail("nothing to parse yet") }

      var parsed = false
      parseReply(ByteString("\r\nfoo\r\n")) { result ⇒
        val expected = Some(ByteString("foo"))
        assert(result === expected)
        parsed = true
      }
      if (!parsed) fail("Did not parse anything")
      assert(remainingBuffer === ByteString.empty)
    }

    it("should handle a bulk string reply split after the first line") {
      parseReply(ByteString("$3\r\n")) { _ ⇒ fail("nothing to parse yet") }

      var parsed = false
      parseReply(ByteString("foo\r\n")) { result ⇒
        val expected = Some(ByteString("foo"))
        assert(result === expected)
        parsed = true
      }
      if (!parsed) fail("Did not parse anything")
      assert(remainingBuffer === ByteString.empty)
    }

    it("should handle an error reply split into two parts") {
      parseReply(ByteString("-")) { _ ⇒ fail("nothing to parse yet") }

      var parsed = false
      parseReply(ByteString("foo\r\n")) { result ⇒
        result match {
          case Some(akka.actor.Status.Failure(e)) ⇒
            assert(e.getMessage === "foo")
          case x ⇒ fail(s"Parsed unexpected message $x")
        }
        parsed = true
      }
      if (!parsed) fail("Did not parse anything")
      assert(remainingBuffer === ByteString.empty)
    }
  }
}
