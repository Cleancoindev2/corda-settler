package net.corda.finance.ripple.utilities

import com.ripple.core.coretypes.hash.Hash256
import net.corda.core.contracts.Amount
import net.corda.core.crypto.SecureHash
import net.corda.finance.AMOUNT
import net.corda.finance.obligation.types.DigitalCurrency
import net.corda.finance.ripple.types.TransactionInfoResponse
import com.ripple.core.coretypes.Amount as XRPAmount

fun TransactionInfoResponse.hasSucceeded() = status == "success" && validated

fun Amount<*>.toXRPAmount(): XRPAmount = XRPAmount.fromString(quantity.toString())

fun SecureHash.toXRPHash(): Hash256 = Hash256.fromHex(toString())

val DEFAULT_XRP_FEE = XRPAmount.fromString("1000")

@JvmField
val Ripple: DigitalCurrency = DigitalCurrency.getInstance("XRP")

fun RIPPLES(amount: Int): Amount<DigitalCurrency> = AMOUNT(amount, Ripple)
fun RIPPLES(amount: Long): Amount<DigitalCurrency> = AMOUNT(amount, Ripple)
fun RIPPLES(amount: Double): Amount<DigitalCurrency> = AMOUNT(amount, Ripple)

val Int.XRP: Amount<DigitalCurrency> get() = RIPPLES(this)
val Long.XRP: Amount<DigitalCurrency> get() = RIPPLES(this)
val Double.XRP: Amount<DigitalCurrency> get() = RIPPLES(this)