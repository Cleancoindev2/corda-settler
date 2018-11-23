package com.r3.corda.finance.obligation.oracle.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.finance.obligation.DigitalCurrency
import com.r3.corda.finance.obligation.PaymentStatus
import com.r3.corda.finance.obligation.SettlementOracleResult
import com.r3.corda.finance.obligation.commands.ObligationCommands
import com.r3.corda.finance.obligation.contracts.ObligationContract
import com.r3.corda.finance.obligation.flows.AbstractSendToSettlementOracle
import com.r3.corda.finance.obligation.oracle.services.XrpOracleService
import com.r3.corda.finance.obligation.states.Obligation
import com.r3.corda.finance.ripple.types.XrpPayment
import com.r3.corda.finance.ripple.types.XrpSettlement
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.time.Duration

@InitiatedBy(AbstractSendToSettlementOracle::class)
class VerifySettlement(val otherSession: FlowSession) : FlowLogic<Unit>() {

    override val progressTracker: ProgressTracker = ProgressTracker()

    enum class VerifyResult { TIMEOUT, SUCCESS, PENDING }

    @Suspendable
    fun verifyXrpSettlement(obligation: Obligation<DigitalCurrency>, xrpPayment: XrpPayment<DigitalCurrency>): VerifyResult {
        val oracleService = serviceHub.cordaService(XrpOracleService::class.java)
        while (true) {
            logger.info("Checking for settlement...")
            val result = oracleService.hasPaymentSettled(xrpPayment, obligation)
            when (result) {
                VerifyResult.SUCCESS, VerifyResult.TIMEOUT -> return result
                // Sleep for five seconds before we try again. The Oracle might receive the request to verify payment
                // before the payment succeed. Also it takes a bit of time for all the nodes to receive the new ledger
                // version. Note: sleep is a suspendable operation.
                VerifyResult.PENDING -> sleep(Duration.ofSeconds(5))
            }
        }
    }

    private fun createTransaction(obligationStateAndRef: StateAndRef<Obligation<DigitalCurrency>>): SignedTransaction {
        val obligation = obligationStateAndRef.state.data
        val settlementInstructions = obligation.settlementMethod as XrpSettlement

        // 4. Update settlement instructions.
        // For now we cannot partially settle the obligation.
        val updatedSettlementInstructions = settlementInstructions.updateStatus(PaymentStatus.ACCEPTED)
        val obligationWithUpdatedStatus = obligation
                .withSettlementMethod(updatedSettlementInstructions)
                .settle(obligation.faceAmount)

        // 4. Build transaction.
        // Change the payment settlementStatus to accepted - this means that the obligation has settled.
        val signingKey = ourIdentity.owningKey
        val notary = serviceHub.networkMapCache.notaryIdentities.firstOrNull()
                ?: throw FlowException("No available notary.")
        val utx = TransactionBuilder(notary = notary).apply {
            addInputState(obligationStateAndRef)
            addCommand(ObligationCommands.Cancel(), signingKey)
            addOutputState(obligationWithUpdatedStatus, ObligationContract.CONTRACT_REF)
        }

        // 5. Sign transaction.
        return serviceHub.signInitialTransaction(utx, signingKey)
    }

    @Suspendable
    override fun call() {
        // 1. Receive the obligation state we are verifying settlement of.
        val obligationStateAndRef = subFlow(ReceiveStateAndRefFlow<Obligation<DigitalCurrency>>(otherSession)).single()
        val obligation = obligationStateAndRef.state.data
        val settlementMethod = obligation.settlementMethod
        // 2. Check there are settlement instructions.
        check(settlementMethod != null) { "This obligation has no settlement method." }
        // 3. As payments are appended to the end of the payments list, we assume we are only checking the last
        // payment. The obligation is sent to the settlement Oracle for EACH payment, so everyone does get checked.
        val lastPayment = obligation.payments.last() as XrpPayment<DigitalCurrency>

        // 4. Handle different settlement methods.
        val verifyResult = when (settlementMethod) {
            is XrpSettlement -> verifyXrpSettlement(obligation, lastPayment)
            else -> throw IllegalStateException("This Oracle only handles XRP settlement.")
        }

        when (verifyResult) {
            VerifyResult.TIMEOUT -> {
                val
                otherSession.send(SettlementOracleResult.Failure("Payment wasn't made by the deadline."))
                return
            }
            VerifyResult.SUCCESS -> {
                val stx = createTransaction(obligationStateAndRef)
                // 6. Finalise transaction and send to participants.
                otherSession.send(SettlementOracleResult.Success(stx))
            }
            else -> throw IllegalStateException("This shouldn't happen!")
        }
    }
}
