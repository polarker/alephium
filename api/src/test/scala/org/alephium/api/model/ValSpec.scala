// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.api.model

import org.alephium.api.{ApiModelCodec, JsonFixture}
import org.alephium.protocol.Hash
import org.alephium.protocol.model.Address
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{Hex, I256, U256}

class ValSpec extends ApiModelCodec with JsonFixture {
  def generateContractAddress(): Address.Contract =
    Address.Contract(LockupScript.p2c("uomjgUz6D4tLejTkQtbNJMY8apAjTm1bgQf7em1wDV7S").get)

  it should "encode/decode Val.Bool" in {
    checkData[Val](Val.True, """{"type": "Bool", "value": true}""")
    checkData[Val](Val.False, """{"type": "Bool", "value": false}""")
  }

  it should "encode/decode Val.ByteVec" in {
    val bytes = Hash.generate.bytes
    checkData[Val](
      Val.ByteVec(bytes),
      s"""{"type": "ByteVec", "value": "${Hex.toHexString(bytes)}"}"""
    )
  }

  it should "encode/decode Val.U256" in {
    checkData[Val](Val.U256(U256.MaxValue), s"""{"type": "U256", "value": "${U256.MaxValue}"}""")
  }

  it should "encode/decode Val.I256" in {
    checkData[Val](Val.I256(I256.MinValue), s"""{"type": "I256", "value": "${I256.MinValue}"}""")
  }

  it should "encode/decode Val.Address" in {
    val address = generateContractAddress()
    checkData[Val](
      Val.Address(address),
      s"""{"type": "Address", "value": "${address.toBase58}"}"""
    )
  }

  it should "encode/decode Vals" in {
    checkData[Val](Val.Bool(true), """{"type":"Bool","value":true}""")
  }
}