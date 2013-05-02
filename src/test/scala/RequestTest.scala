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
      val request = Request(ByteString("SET"), ByteString("mykey"), ByteString("somevalue"))
      val expected = ByteString("*3\r\n$3\r\nSET\r\n$5\r\nmykey\r\n$9\r\nsomevalue\r\n")

      assert(request.toByteString === expected)
    }
  }

}