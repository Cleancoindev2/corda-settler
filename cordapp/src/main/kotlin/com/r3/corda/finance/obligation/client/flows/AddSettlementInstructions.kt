package com.r3.corda.finance.obligation.client.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.finance.obligation.Obligation
import com.r3.corda.finance.obligation.ObligationContract
import com.r3.corda.finance.obligation.SettlementInstructions
import com.r3.corda.finance.obligation.getLinearStateById
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

@InitiatingFlow
@StartableByRPC
class AddSettlementInstructions(
        val linearId: UniqueIdentifier,
        private val settlementInstructions: SettlementInstructions
) : FlowLogic<SignedTransaction>() {

    companion object {
        object INITIALISING : ProgressTracker.Step("Performing initial steps.")
        object BUILDING : ProgressTracker.Step("Building and verifying transaction.")
        object SIGNING : ProgressTracker.Step("signing transaction.")

        object FINALISING : ProgressTracker.Step("Finalising transaction.") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(INITIALISING, BUILDING, SIGNING, FINALISING)
    }

    override val progressTracker: ProgressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {
        // 1. Retrieve obligation.
        progressTracker.currentStep = INITIALISING
        val obligationStateAndRef = getLinearStateById<Obligation<Any>>(linearId, serviceHub)
                ?: throw IllegalArgumentException("LinearId not recognised.")
        val obligation = obligationStateAndRef.state.data

        // 2. This flow should only be started by the beneficiary.
        val identityResolver = { abstractParty: AbstractParty ->
            serviceHub.identityService.requireWellKnownPartyFromAnonymous(abstractParty)
        }
        val obligee = obligation.withWellKnownIdentities(identityResolver).obligee
        check(ourIdentity == obligee) { "This flow can only be started by the obligee. " }

        // 3. Add settlement instructions.
        val obligationWithSettlementTerms = obligation.withSettlementTerms(settlementInstructions)

        // 4. Build transaction which adds settlement terms.
        progressTracker.currentStep = BUILDING
        val signingKey = listOf(obligationWithSettlementTerms.obligee.owningKey)
        val notary = serviceHub.networkMapCache.notaryIdentities.firstOrNull()
                ?: throw FlowException("No available notary.")
        val utx = TransactionBuilder(notary = notary).apply {
            addInputState(obligationStateAndRef)
            addOutputState(obligationWithSettlementTerms, ObligationContract.CONTRACT_REF)
            addCommand(ObligationContract.Commands.AddSettlementTerms(), signingKey)
        }

        // 5. Sign transaction.
        progressTracker.currentStep = SIGNING
        val stx = serviceHub.signInitialTransaction(utx, signingKey)

        // 6. Finalise transaction and send to participants.
        progressTracker.currentStep = FINALISING
        return subFlow(FinalityFlow(stx, FINALISING.childProgressTracker()))
    }

}