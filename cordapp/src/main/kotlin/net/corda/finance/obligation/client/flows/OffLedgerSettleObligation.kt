package net.corda.finance.obligation.client.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.finance.obligation.client.getLinearStateById
import net.corda.finance.obligation.contracts.Obligation
import net.corda.finance.obligation.types.OffLedgerSettlementInstructions
import net.corda.finance.obligation.types.OnLedgerSettlementTerms

@StartableByRPC
class OffLedgerSettleObligation(private val linearId: UniqueIdentifier) : FlowLogic<SignedTransaction>() {

    companion object {
        object DETERMINING : ProgressTracker.Step("Determining settlement method.")
        object SETTLING : ProgressTracker.Step("Settling obligation.")
        object CHECKING : ProgressTracker.Step("Checking settlement.")

        fun tracker() = ProgressTracker(DETERMINING, SETTLING, CHECKING)
    }

    override val progressTracker: ProgressTracker = tracker()

    private fun getFlowInstance(
            settlementInstructions: OffLedgerSettlementInstructions<*>,
            obligationStateAndRef: StateAndRef<Obligation.State<*>>
    ): FlowLogic<SignedTransaction> {
        val paymentFlowClass = settlementInstructions.paymentFlow
        val paymentFlowClassConstructor = paymentFlowClass.getDeclaredConstructor(
                StateAndRef::class.java,
                OffLedgerSettlementInstructions::class.java
        )
        return paymentFlowClassConstructor.newInstance(obligationStateAndRef, settlementInstructions)
    }

    @Suspendable
    override fun call(): SignedTransaction {
        // The settlement instructions determine how this obligation should be settled.
        progressTracker.currentStep = DETERMINING
        val obligationStateAndRef = getLinearStateById<Obligation.State<*>>(linearId, serviceHub)
                ?: throw IllegalArgumentException("LinearId not recognised.")
        val obligationState = obligationStateAndRef.state.data
        val settlementInstructions = obligationState.settlementInstructions

        progressTracker.currentStep = SETTLING
        val ftx = when (settlementInstructions) {
            is OnLedgerSettlementTerms -> throw IllegalStateException("Obligation to be settled on-ledger. Aborting ")
            is OffLedgerSettlementInstructions<*> -> subFlow(getFlowInstance(settlementInstructions, obligationStateAndRef))
            else -> throw IllegalStateException("No settlement instructions added to obligation.")
        }

        progressTracker.currentStep = CHECKING
        val potentiallySettledObligation = ftx.tx.outRefsOfType<Obligation.State<*>>().single()
        potentiallySettledObligation.state.data.settlementInstructions
        // Checks the payment settled.
        // We only supply the linear ID because this flow can be called from the shell on its own.
        return subFlow(SendToSettlementOracle(linearId))
    }

}