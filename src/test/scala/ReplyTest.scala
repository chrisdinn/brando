package brando

import org.scalatest.FunSpec
import akka.util.ByteString

class ReplyTest extends FunSpec {

  describe("Status reply") {
    it("should decode Ok") {
      val reply = Reply(ByteString("+OK\r\n"))

      assert(reply === Reply.Ok)
    }

    it("should decode Pong") {
      val reply = Reply(ByteString("+PONG\r\n"))

      assert(reply === Reply.Pong)
    }
  }

  describe("Integer reply") {
    it("should decode as integer") {
      val reply = Reply(ByteString(":17575\r\n"))

      assert(reply === Some(17575))
    }
  }

  describe("Bulk reply") {
    it("should decode as ByteString option") {
      val reply = Reply(ByteString("$6\r\nfoobar\r\n"))

      assert(reply === Some(ByteString("foobar")))
    }

    it("should decode null as None") {
      val reply = Reply(ByteString("$-1\r\n"))

      assert(reply === None)
    }
  }

  describe("Multi Bulk reply") {
    it("should decode list of bulk reply values") {
      val reply = Reply(ByteString("*4\r\n$3\r\nfoo\r\n$3\r\nbar\r\n$4\r\nfoob\r\n$6\r\nfoobar\r\n"))

      assert(reply === List(Some(ByteString("foo")), Some(ByteString("bar")),
        Some(ByteString("foob")), Some(ByteString("foobar"))))
    }

    it("should decode list of with nil values") {
      val reply = Reply(ByteString("*3\r\n$-1\r\n$3\r\nbar\r\n$6\r\nfoobar\r\n"))

      assert(reply === List(None, Some(ByteString("bar")), Some(ByteString("foobar"))))
    }

    it("should decode list of with integer values") {
      val reply = Reply(ByteString("*3\r\n$3\r\nbar\r\n:37282\r\n$6\r\nfoobar\r\n"))

      assert(reply === List(Some(ByteString("bar")), Some(37282), Some(ByteString("foobar"))))
    }

    it("should decode list of with nested multi bulk reply") {
      val reply = Reply(ByteString("*3\r\n$3\r\nbar\r\n*4\r\n$3\r\nfoo\r\n$3\r\nbar\r\n$4\r\nfoob\r\n$6\r\nfoobar\r\n$6\r\nfoobaz\r\n"))

      assert(reply === List(Some(ByteString("bar")), List(Some(ByteString("foo")), Some(ByteString("bar")),
        Some(ByteString("foob")), Some(ByteString("foobar"))), Some(ByteString("foobaz"))))
    }
  }

}