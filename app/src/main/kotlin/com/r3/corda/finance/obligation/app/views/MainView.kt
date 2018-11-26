package com.r3.corda.finance.obligation.app.views

import com.r3.corda.finance.obligation.app.controllers.NetworkMapController
import com.r3.corda.finance.obligation.app.controllers.ObligationsController
import com.r3.corda.finance.obligation.app.formatAmount
import com.r3.corda.finance.obligation.app.models.UserModel
import com.r3.corda.finance.obligation.states.Obligation
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.scene.control.TableColumn
import javafx.stage.StageStyle
import net.corda.core.messaging.CordaRPCOps
import tornadofx.*

class MainView : View("Corda Settler") {
    val cordaRpcOps: CordaRPCOps by param()
    private val user: UserModel by inject()

    private val obligationsController: ObligationsController by inject(params = mapOf("cordaRpcOps" to cordaRpcOps))
    private val networkMapController: NetworkMapController by inject(params = mapOf("cordaRpcOps" to cordaRpcOps))

    override val root = borderpane {
        top {
            vbox {
                hbox {
                    label(user.username)
                    label(user.hostAndPort)
                }
                vbox {
                    combobox<String> {
                        items = FXCollections.observableArrayList("GBP", "XRP", "USD")
                    }
                }

                tableview(obligationsController.obligations) {
                    columnResizePolicy = SmartResize.POLICY
                    readonlyColumn("Due By", Obligation<*>::dueBy)
                    readonlyColumn("Amount", Obligation<*>::faceAmount).cellFormat { text = formatAmount(it) }
                    column("Cpty") { it: TableColumn.CellDataFeatures<Obligation<*>, String> ->
                        val obligation = it.value
                        val counterparty = if (obligation.obligor == networkMapController.us.value) {
                            obligation.obligee
                        } else obligation.obligor
                        SimpleStringProperty(counterparty.nameOrNull()?.organisation)
                    }
                }
            }
        }
        bottom {
            toolbar {
                button("Add obligation").action {
                    val params = mapOf(CreateObligationView::cordaRpcOps to cordaRpcOps)
                    find<CreateObligationView>(params).openWindow(stageStyle = StageStyle.UTILITY)
                }
            }
        }
    }
}

