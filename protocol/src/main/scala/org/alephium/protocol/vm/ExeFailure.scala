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

package org.alephium.protocol.vm

import java.math.BigInteger

import org.alephium.io.IOError
import org.alephium.protocol.model.ContractId
import org.alephium.serde.SerdeError
import org.alephium.util.U256

// scalastyle:off number.of.types
trait ExeFailure extends Product {
  def name: String = productPrefix
}

case object CodeSizeTooLarge                                   extends ExeFailure
case object FieldsSizeTooLarge                                 extends ExeFailure
case object ExpectStatefulFrame                                extends ExeFailure
case object InvalidFinalState                                  extends ExeFailure
case object StackOverflow                                      extends ExeFailure
case object StackUnderflow                                     extends ExeFailure
case object NegativeArgumentInStack                            extends ExeFailure
case object InsufficientSignatures                             extends ExeFailure
case object TooManySignatures                                  extends ExeFailure
case object InvalidPublicKey                                   extends ExeFailure
case object SignedDataIsNot32Bytes                             extends ExeFailure
case object InvalidSignatureFormat                             extends ExeFailure
case object InvalidSignature                                   extends ExeFailure
case object InvalidTxInputIndex                                extends ExeFailure
case object NoTxInput                                          extends ExeFailure
case object TxInputAddressesAreNotIdentical                    extends ExeFailure
case object AccessTxInputAddressInContract                     extends ExeFailure
case object LockTimeOverflow                                   extends ExeFailure
case object AbsoluteLockTimeVerificationFailed                 extends ExeFailure
case object RelativeLockTimeVerificationFailed                 extends ExeFailure
case object RelativeLockTimeExpectPersistedUtxo                extends ExeFailure
case object InvalidBoolean                                     extends ExeFailure
case object IntegerOverFlow                                    extends ExeFailure
final case class ArithmeticError(message: String)              extends ExeFailure
case object InvalidVarIndex                                    extends ExeFailure
case object InvalidVarType                                     extends ExeFailure
case object InvalidFieldIndex                                  extends ExeFailure
case object InvalidFieldLength                                 extends ExeFailure
case object TooManyFields                                      extends ExeFailure
case object InvalidFieldType                                   extends ExeFailure
case object EmptyMethods                                       extends ExeFailure
final case class InvalidType(v: Val)                           extends ExeFailure
case object InvalidMethod                                      extends ExeFailure
case object InvalidMethodModifierBeforeLeman                   extends ExeFailure
final case class InvalidMethodIndex(index: Int)                extends ExeFailure
final case class InvalidMethodArgLength(got: Int, expect: Int) extends ExeFailure
case object InvalidReturnLength                                extends ExeFailure
case object InvalidExternalMethodReturnLength                  extends ExeFailure
case object InvalidArgLength                                   extends ExeFailure
case object InvalidExternalMethodArgLength                     extends ExeFailure
case object InvalidLengthForEncodeInstr                        extends ExeFailure
case object InsufficientArgs                                   extends ExeFailure
case object ExternalPrivateMethodCall                          extends ExeFailure
case object AssertionFailed                                    extends ExeFailure
case object InvalidInstrOffset                                 extends ExeFailure
case object PcOverflow                                         extends ExeFailure
case object NonEmptyReturnForMainFunction                      extends ExeFailure
final case class InvalidConversion(from: Val, to: Val.Type)    extends ExeFailure
final case class SerdeErrorCreateContract(error: SerdeError)   extends ExeFailure
final case class NonExistContract(contractId: ContractId)      extends ExeFailure
case object ContractDestructionShouldNotBeCalledFromSelf       extends ExeFailure
case object PayToContractAddressNotInCallerTrace               extends ExeFailure
case object InvalidAddressTypeInContractDestroy                extends ExeFailure
case object NonExistTxInput                                    extends ExeFailure
case object InvalidContractAddress                             extends ExeFailure
case object ExpectNonPayableMethod                             extends ExeFailure
case object ExpectStatefulContractObj                          extends ExeFailure
case object NoBalanceAvailable                                 extends ExeFailure
case object NotEnoughBalance                                   extends ExeFailure
case object NoAssetsApproved                                   extends ExeFailure
case object BalanceOverflow                                    extends ExeFailure
case object NoAlphBalanceForTheAddress                         extends ExeFailure
case object NoTokenBalanceForTheAddress                        extends ExeFailure
case object InvalidBalances                                    extends ExeFailure
case object BalanceErrorWhenSwitchingBackFrame                 extends ExeFailure
case object LowerThanContractMinimalBalance                    extends ExeFailure
case object UnableToPayGasFee                                  extends ExeFailure
case object InvalidOutputBalances                              extends ExeFailure
case object InvalidTokenId                                     extends ExeFailure
case object InvalidContractId                                  extends ExeFailure
case object ExpectAContract                                    extends ExeFailure
case object OutOfGas                                           extends ExeFailure
case object ContractPoolOverflow                               extends ExeFailure
case object ContractFieldOverflow                              extends ExeFailure
final case class ContractLoadDisallowed(id: ContractId)        extends ExeFailure
case object ContractAssetAlreadyInUsing                        extends ExeFailure
case object ContractAssetAlreadyFlushed                        extends ExeFailure
case object ContractAssetUnloaded                              extends ExeFailure
case object EmptyContractAsset                                 extends ExeFailure
case object NoCaller                                           extends ExeFailure
final case class NegativeTimeStamp(millis: Long)               extends ExeFailure
final case class InvalidTarget(value: BigInteger)              extends ExeFailure
case object InvalidBytesSliceArg                               extends ExeFailure
case object InvalidBytesSize                                   extends ExeFailure
case object InvalidSizeForZeros                                extends ExeFailure
final case class SerdeErrorByteVecToAddress(error: SerdeError) extends ExeFailure
case object FailedInRecoverEthAddress                          extends ExeFailure
case object UnexpectedRecursiveCallInMigration                 extends ExeFailure
case object InvalidAssetAddress                                extends ExeFailure
case object UnexpectedApprovedAssets                           extends ExeFailure
final case class ContractAlreadyExists(contractId: ContractId) extends ExeFailure

final case class UncaughtKeyNotFoundError(error: IOError.KeyNotFound) extends ExeFailure
final case class UncaughtSerdeError(error: IOError.Serde)             extends ExeFailure

final case class InactiveInstr[-Ctx <: StatelessContext](instr: Instr[Ctx]) extends ExeFailure
final case class PartiallyEnabledInstr[-Ctx <: StatelessContext](instr: Instr[Ctx])
    extends ExeFailure

final case class InvalidErrorCode(errorCode: U256) extends ExeFailure
final case class AssertionFailedWithErrorCode(contractIdOpt: Option[ContractId], errorCode: Int)
    extends ExeFailure {
  override def toString: String = {
    val contractIdString = contractIdOpt match {
      case Some(contractId) => contractId.toHexString
      case None             => ""
    }
    s"AssertionFailedWithErrorCode($contractIdString,$errorCode)"
  }
}

sealed trait IOFailure extends Product {
  def error: IOError
  def name: String = productPrefix
}
final case class IOErrorUpdateState(error: IOError)     extends IOFailure
final case class IOErrorRemoveContract(error: IOError)  extends IOFailure
final case class IOErrorLoadContract(error: IOError)    extends IOFailure
final case class IOErrorLoadOutputs(error: IOError)     extends IOFailure
final case class IOErrorMigrateContract(error: IOError) extends IOFailure
final case class IOErrorWriteLog(error: IOError)        extends IOFailure
