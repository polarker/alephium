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

package org.alephium.ralph

import org.alephium.protocol.vm._
import org.alephium.ralph.BuiltIn.{OverloadedSimpleBuiltIn, SimpleBuiltIn}
import org.alephium.util.AlephiumSpec

class BuiltInSpec extends AlephiumSpec {
  it should "check all functions that can use preapproved assets" in {
    BuiltIn.statelessFuncs.values.count(_.usePreapprovedAssets) is 0
    BuiltIn.statefulFuncs.values.filter(_.usePreapprovedAssets).toSet is
      Set[BuiltIn.BuiltIn[StatefulContext]](
        BuiltIn.lockApprovedAssets,
        BuiltIn.createContract,
        BuiltIn.createContractWithToken,
        BuiltIn.copyCreateContract,
        BuiltIn.copyCreateContractWithToken,
        BuiltIn.createSubContract,
        BuiltIn.createSubContractWithToken,
        BuiltIn.copyCreateSubContract,
        BuiltIn.copyCreateSubContractWithToken
      )
  }

  it should "check all functions that can use assets in contract" in {
    BuiltIn.statelessFuncs.values.count(_.useAssetsInContract) is 0
    BuiltIn.statefulFuncs.values
      .filter(_.useAssetsInContract)
      .flatMap {
        case f: SimpleBuiltIn[_] => f.instrs
        case f: OverloadedSimpleBuiltIn[_] =>
          f.argsTypeWithInstrs(0)
            .instrs
            .asInstanceOf[Seq[Instr[_]]]
        case _: Any => Seq.empty[Instr[_]]
      }
      .toSet is StaticAnalysis.contractAssetsInstrs.--(
      Set(SelfAddress, TransferAlphFromSelf, TransferAlphToSelf)
    )
  }
}