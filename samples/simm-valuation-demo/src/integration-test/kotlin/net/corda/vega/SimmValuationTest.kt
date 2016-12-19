package net.corda.vega

import com.opengamma.strata.product.common.BuySell
import net.corda.core.getOrThrow
import net.corda.core.node.services.ServiceInfo
import net.corda.node.driver.driver
import net.corda.node.services.transactions.SimpleNotaryService
import net.corda.testing.IntegrationTestCategory
import net.corda.testing.getHostAndPort
import net.corda.testing.http.HttpApi
import net.corda.vega.api.PortfolioApi
import net.corda.vega.api.PortfolioApiUtils
import net.corda.vega.api.SwapDataModel
import net.corda.vega.portfolio.Portfolio
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.*

class SimmValuationTest: IntegrationTestCategory {
    private companion object {
        // SIMM demo can only currently handle one valuation date due to a lack of market data or a market data source.
        val valuationDate = LocalDate.parse("2016-06-06")
        val nodeALegalName = "Bank A"
        val nodeBLegalName = "Bank B"
    }

    @Test fun `runs SIMM valuation demo`() {
        driver(isDebug = true) {
            startNode("Controller", setOf(ServiceInfo(SimpleNotaryService.type))).getOrThrow()
            val nodeAAddr = startNode(nodeALegalName).getOrThrow().config.getHostAndPort("webAddress")
            val nodeBAddr = startNode(nodeBLegalName).getOrThrow().config.getHostAndPort("webAddress")

            val nodeA = HttpApi.fromHostAndPort(nodeAAddr, "api/simmvaluationdemo")
            val nodeB = HttpApi.fromHostAndPort(nodeBAddr, "api/simmvaluationdemo")

            val nodeBParty = getAvailablePartiesFor(nodeA).counterparties.single { it.text == nodeBLegalName }
            val nodeAParty = getAvailablePartiesFor(nodeB).counterparties.single { it.text == nodeALegalName }

            assert(createTradeBetween(nodeA, nodeBParty))
            assert(tradeExists(nodeB, nodeAParty))
            assert(runValuationsBetween(nodeA, nodeBParty))
            assert(valuationExists(nodeB, nodeAParty))
        }
    }

    private fun getAvailablePartiesFor(api: HttpApi): PortfolioApi.AvailableParties {
        return api.getJson<PortfolioApi.AvailableParties>("whoami")
    }

    private fun createTradeBetween(api: HttpApi, counterparty: PortfolioApi.ApiParty): Boolean {
        val trade = SwapDataModel("trade1", "desc", valuationDate, "EUR_FIXED_1Y_EURIBOR_3M",
                valuationDate, LocalDate.parse("2020-01-02"), BuySell.BUY, BigDecimal.valueOf(1000), BigDecimal.valueOf(0.1))
        return api.putJson("${counterparty.id}/trades", trade)
    }

    private fun tradeExists(api: HttpApi, counterparty: PortfolioApi.ApiParty): Boolean {
        val trades = api.getJson<List<Map<String, Any>>>("${counterparty.id}/trades")
        return (!trades.isEmpty())
    }

    private fun runValuationsBetween(api: HttpApi, counterparty: PortfolioApi.ApiParty): Boolean {
        return api.postJson("${counterparty.id}/portfolio/valuations/calculate", PortfolioApi.ValuationCreationParams(valuationDate))
    }

    private fun valuationExists(api: HttpApi, counterparty: PortfolioApi.ApiParty): Boolean {
        val valuations = api.getJson<PortfolioApiUtils.ValuationsView>("${counterparty.id}/portfolio/valuations")
        return (valuations.initialMargin.call["total"] != 0.0)
    }
}