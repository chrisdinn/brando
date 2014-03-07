package brando

import org.scalatest.FunSpec
import akka.util.ByteString

class RequestTest extends FunSpec {
  describe("toByteString") {
    it("should encode request as Redis protocol ByteString") {
      val request = Request("GET", "mykey")
      val expected = ByteString("*2\r\n$3\r\nGET\r\n$5\r\nmykey\r\n")

      assert(request.toByteString === expected)
    }

    it("should encode request with 2 arguments") {
      val request = Request("SET", "mykey", "somevalue")
      val expected = ByteString("*3\r\n$3\r\nSET\r\n$5\r\nmykey\r\n$9\r\nsomevalue\r\n")

      assert(request.toByteString === expected)
    }
  }

  describe("HashRequest") {
    it("should create a request that contains all the arguments merged together") {
      val req = HashRequest("HMSET", "setkey", Map("a" -> "a", "b" -> "b"))
      assertResult(Request("HMSET", "setkey", "a", "a", "b", "b"))(req)
    }
  }

}