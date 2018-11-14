package com.r3.corda.finance.ripple.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.finance.obligation.OffLedgerSettlementInstructions
import com.r3.corda.finance.obligation.PaymentReference
import com.r3.corda.finance.obligation.PaymentStatus
import com.r3.corda.finance.obligation.client.flows.MakeOffLedgerPayment
import com.r3.corda.finance.obligation.contracts.Obligation
import com.r3.corda.finance.ripple.services.XRPService
import com.r3.corda.finance.ripple.types.SubmitPaymentResponse
import com.r3.corda.finance.ripple.types.XRPSettlementInstructions
import com.r3.corda.finance.ripple.utilities.*
import com.ripple.core.coretypes.uint.UInt32
import net.corda.core.contracts.Amount
import net.corda.core.contracts.StateAndRef
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowException
import java.time.Duration
import com.ripple.core.coretypes.Amount as RippleAmount

/**

For testing...

Address     ra6mzL1Xy9aN5eRdjzn9CHTMwcczG1uMpN
Secret      sasKgJbTbka3ahFew2BZybfNg494C
Balance     10,000 XRP

Address     rNmkj4AtjEHJh3D9hMRC4rS3CXQ9mX4S4b
Secret      ssn8cYYksFFexYq97sw9UnvLnMKgh
Balance     10,000 XRP
 */
class MakeXRPPayment(
        obligationStateAndRef: StateAndRef<Obligation.State<*>>,
        override val settlementInstructions: OffLedgerSettlementInstructions<*>
) : MakeOffLedgerPayment(obligationStateAndRef, settlementInstructions) {

    var seqNo: UInt32? = null

    /** Don't want to serialize this. */
    private fun getSequenceNumber(): UInt32 {
        val xrpService = serviceHub.cordaService(XRPService::class.java).client
        val accountId = xrpService.address
        return xrpService.nextSequenceNumber(accountId)
    }

    @Suspendable
    override fun setup() {
        // Get a new sequence number.
        seqNo = getSequenceNumber()
        // Checkpoint the flow here.
        // * If the flow dies before payment, the payment should still happen.
        // * If the flow dies after payment and is replayed from this point, then the second payment will fail.
        sleep(Duration.ofMillis(1))
    }

    override fun checkBalance(requiredAmount: Amount<*>) {
        // Get a XRPService client.
        val xrpClient = serviceHub.cordaService(XRPService::class.java).client
        // Check the balance on the supplied XRPService address.
        val ourAccountInfo = xrpClient.accountInfo(xrpClient.address)
        val balance = ourAccountInfo.accountData.balance
        check(balance > requiredAmount.toXRPAmount()) {
            "You do not have enough XRP to make the payment."
        }
    }

    /** Don't want to serialize this. */
    private fun createAndSignAndSubmitPayment(obligation: Obligation.State<*>): SubmitPaymentResponse {
        val xrpService = serviceHub.cordaService(XRPService::class.java).client
        // 1. Create a new payment.
        // This function will always use the sequence number which was obtained before the flow check-point.
        // So if this flow is restarted and the payment were to be made twice then the Ripple node will return an error
        // because the same sequence number will be used twice.
        val payment = xrpService.createPayment(
                // Always use the sequence number provided. It will never be null at this point.
                sequence = seqNo!!,
                source = xrpService.address,
                destination = (settlementInstructions as XRPSettlementInstructions).accountToPay,
                amount = obligation.faceAmount.toXRPAmount(),
                fee = DEFAULT_XRP_FEE,
                linearId = SecureHash.sha256(obligation.linearId.id.toString()).toXRPHash()
        )

        // 2. Sign the payment.
        val signedPayment = xrpService.signPayment(payment)
        return xrpService.submitTransaction(signedPayment)
    }

    @Suspendable
    override fun makePayment(obligation: Obligation.State<*>): PaymentReference {
        check(settlementInstructions.paymentStatus == PaymentStatus.NOT_SENT) {
            "An XRP payment payment has already been made to settle this obligation."
        }

        // Create, sign and submit a payment request then store the transaction hash and checkpoint.
        // Fail if there is any exception as
        val paymentResponse = try {
            createAndSignAndSubmitPayment(obligation)
        } catch (e: AlreadysubmittedException) {
            logger.warn(e.message)
            throw FlowException("The transaction was already submitted. However, " +
                    "the node failed before check-pointing the transaction hash. " +
                    "Please check your XRP payments to obtain the transaction hash " +
                    "so you can update the obligation state with the payment reference " +
                    "by starting the UpdateObligationWithPaymentRef flow.")
        } catch (e: IncorrectSequenceNumberException) {
            logger.warn(e.message)
            throw FlowException("An incorrect sequence number was used. This could be " +
                    "due to a race with another application to submit a ripple transaction." +
                    "Restarting this the MakeOffLedgerPayment flow for the same obligation" +
                    "should fix this.")
        }

        // Check-point once we have stored the payment reference.
        // If the flow fails from this point, we will just return the payment reference
        val paymentReference = paymentResponse.txJson.hash
        sleep(Duration.ofMillis(1))

        // Return the payment hash.
        return paymentReference
    }
}
