package app.trillopos.sales;

import static app.trillopos.support.Api.json;
import static app.trillopos.support.Api.read;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import app.trillopos.support.Api;
import app.trillopos.support.Api.Owner;
import app.trillopos.support.IntegrationTest;

class SaleWorkflowApiTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    Api api;
    Owner owner;
    String product, customer, shift;

    @BeforeEach void shop() throws Exception {
        api=new Api(mvc); owner=api.signup();
        product=api.stockedProduct(owner,"Workflow oil",1000,20,600);
        api.call(json(patch("/products/"+product),"{\"sellOnline\":true}"),owner.token()).andExpect(status().isOk());
        customer=read(api.call(json(post("/customers"), "{\"name\":\"Online customer\",\"creditLimit\":50000}"),owner.token())
                .andExpect(status().isCreated()),"$.id");
        shift=api.openShift(owner,50000);
    }

    String sale(String channel, int qty, String payments) throws Exception {
        return read(api.call(json(post("/sales/checkout"), """
            {"idempotencyKey":"%s","locationId":"%s","channel":"%s","cashierShiftId":%s,"customerId":"%s",
             "lines":[{"productId":"%s","quantity":%d}],"payments":%s}
            """.formatted(java.util.UUID.randomUUID(),owner.mainLocationId(),channel,channel.equals("POS") ? "\""+shift+"\"" : "null",customer,product,qty,payments)),owner.token())
            .andExpect(status().isCreated()),"$.id");
    }
    String deposit() throws Exception { return sale("ONLINE",3,"[{\"method\":\"CASH\",\"amount\":1000},{\"method\":\"CREDIT\",\"amount\":2000}]"); }
    void state(String id,String progress,String payment,int received,int refunded,int outstanding) throws Exception {
        api.call(get("/sales/"+id),owner.token()).andExpect(status().isOk())
            .andExpect(jsonPath("$.progress").value(progress)).andExpect(jsonPath("$.paymentState.status").value(payment))
            .andExpect(jsonPath("$.paymentState.receivedAmount").value(received))
            .andExpect(jsonPath("$.paymentState.refundedAmount").value(refunded))
            .andExpect(jsonPath("$.paymentState.outstandingAmount").value(outstanding));
    }
    String cancelBody(boolean restock,String key) {
        return "{\"reason\":\"Customer canceled\",\"restock\":"+restock+",\"refundMethod\":\"CASH\",\"cashierShiftId\":\""+shift+"\",\"idempotencyKey\":\""+key+"\"}";
    }

    @Test void onlineAndCounterProgressAreIndependentOfPayment() throws Exception {
        state(deposit(),"OPEN","DEPOSIT",1000,0,2000);
        state(sale("ONLINE",1,"[{\"method\":\"CREDIT\"}]"),"OPEN","UNPAID",0,0,1000);
        state(sale("POS",1,"[{\"method\":\"CASH\"}]"),"CLOSED","PAID",1000,0,0);
    }

    @Test void closingAndReopeningPreserveStockAndDebtAndRecordAnAudit() throws Exception {
        String id=deposit();
        api.call(json(post("/sales/"+id+"/progress"),"{\"progress\":\"CLOSED\",\"reason\":\"Delivered\"}"),owner.token()).andExpect(status().isOk());
        state(id,"CLOSED","DEPOSIT",1000,0,2000);
        api.call(json(post("/sales/"+id+"/progress"),"{\"progress\":\"OPEN\",\"reason\":\"Delivery issue\"}"),owner.token()).andExpect(status().isOk());
        state(id,"OPEN","DEPOSIT",1000,0,2000);
        api.call(get("/sales/"+id+"/progress"),owner.token()).andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].reason").value("Delivered")).andExpect(jsonPath("$[0].changedBy").isNotEmpty());
        api.call(get("/stock-balances?productId="+product),owner.token()).andExpect(jsonPath("$[0].quantity").value(17));
    }

    @Test void laterRepaymentsIncludingTheExistingReceivablesEndpointDrivePaymentState() throws Exception {
        String id=deposit();
        String body="{\"amount\":500,\"method\":\"CASH\",\"locationId\":\""+owner.mainLocationId()+"\",\"cashierShiftId\":\""+shift+"\",\"idempotencyKey\":\"collect-1\"}";
        api.call(json(post("/sales/"+id+"/payments"),body),owner.token()).andExpect(status().isOk());
        api.call(json(post("/sales/"+id+"/payments"),body),owner.token()).andExpect(jsonPath("$.replayed").value(true));
        state(id,"OPEN","DEPOSIT",1500,0,1500);
        String receivable=read(api.call(get("/sales/"+id),owner.token()),"$.paymentState.receivableId");
        api.call(json(post("/receivables/"+receivable+"/settlements"),"{\"amount\":1500,\"method\":\"KBZ_PAY\",\"locationId\":\""+owner.mainLocationId()+"\",\"idempotencyKey\":\"collect-2\"}"),owner.token()).andExpect(status().isCreated());
        state(id,"OPEN","PAID",3000,0,0);
    }

    @Test void depositCancellationRefundsOnlyReceivedMoneyReleasesDebtAndRestocksOnce() throws Exception {
        String id=sale("POS",3,"[{\"method\":\"CASH\",\"amount\":1000},{\"method\":\"CREDIT\",\"amount\":2000}]");
        var result=api.call(json(post("/sales/"+id+"/cancel"),cancelBody(true,"cancel-1")),owner.token()).andExpect(status().isOk());
        String ret=read(result,"$.returnId");
        api.call(get("/returns/"+ret),owner.token()).andExpect(jsonPath("$.refundAmount").value(3000))
            .andExpect(jsonPath("$.creditRefundAmount").value(2000));
        state(id,"CANCELED","REFUNDED",1000,1000,0);
        api.call(json(post("/sales/"+id+"/cancel"),cancelBody(true,"cancel-1")),owner.token()).andExpect(jsonPath("$.replayed").value(true));
        api.call(get("/stock-balances?productId="+product),owner.token()).andExpect(jsonPath("$[0].quantity").value(20));
        api.call(get("/shifts/"+shift+"/drawer"),owner.token()).andExpect(jsonPath("$.cashRefunds").value(1000))
            .andExpect(jsonPath("$.expectedCash").value(50000));
        api.call(json(post("/sales/"+id+"/progress"),"{\"progress\":\"OPEN\",\"reason\":\"Retry\"}"),owner.token()).andExpect(status().isConflict());
        api.call(json(post("/sales/"+id+"/payments"),"{\"amount\":1,\"method\":\"CASH\",\"locationId\":\""+owner.mainLocationId()+"\",\"idempotencyKey\":\"after-cancel\"}"),owner.token()).andExpect(status().isConflict());
    }

    @Test void unpaidCancellationMovesNoCashAndIsNotLabeledRefunded() throws Exception {
        String id=sale("ONLINE",3,"[{\"method\":\"CREDIT\"}]");
        api.call(json(post("/sales/"+id+"/cancel"),cancelBody(true,"unpaid")),owner.token()).andExpect(status().isOk());
        state(id,"CANCELED","UNPAID",0,0,0);
        api.call(get("/shifts/"+shift+"/drawer"),owner.token()).andExpect(jsonPath("$.cashRefunds").value(0));
    }

    @Test void ordinaryCashReturnsCannotInventARefundOnUnpaidSales() throws Exception {
        String id=sale("ONLINE",3,"[{\"method\":\"CREDIT\"}]");
        String line=read(api.call(get("/sales/"+id),owner.token()),"$.lines[0].id");
        api.call(json(post("/returns"),"{\"saleId\":\""+id+"\",\"locationId\":\""+owner.mainLocationId()+"\",\"refundMethod\":\"CASH\",\"lines\":[{\"saleLineId\":\""+line+"\",\"quantity\":1}]}"),owner.token())
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("refund_exceeds_received"));
        state(id,"OPEN","UNPAID",0,0,3000);
        api.call(get("/stock-balances?productId="+product),owner.token()).andExpect(jsonPath("$[0].quantity").value(17));
    }

    @Test void cancellationAfterPartialReturnOnlyReversesTheRemainingGoodsAndHonorsNoRestock() throws Exception {
        String id=deposit();
        String line=read(api.call(get("/sales/"+id),owner.token()),"$.lines[0].id");
        api.call(json(post("/returns"),"{\"saleId\":\""+id+"\",\"locationId\":\""+owner.mainLocationId()+"\",\"refundMethod\":\"CASH\",\"lines\":[{\"saleLineId\":\""+line+"\",\"quantity\":1}]}"),owner.token()).andExpect(status().isCreated());
        state(id,"OPEN","UNPAID",1000,1000,2000);
        api.call(json(post("/sales/"+id+"/cancel"),cancelBody(false,"remaining")),owner.token()).andExpect(status().isOk());
        state(id,"CANCELED","REFUNDED",1000,1000,0);
        api.call(get("/stock-balances?productId="+product),owner.token()).andExpect(jsonPath("$[0].quantity").value(18));
    }

    @Test void statusFiltersApplyBeforeLimitAndTenantIsolationAppliesToAllActions() throws Exception {
        String unpaid=sale("ONLINE",1,"[{\"method\":\"CREDIT\"}]");
        sale("ONLINE",1,"[{\"method\":\"CASH\"}]");
        api.call(get("/sales?progress=OPEN&paymentStatus=UNPAID&limit=1"),owner.token())
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(unpaid));
        Owner other=api.signup();
        api.call(get("/sales?paymentStatus=UNPAID"),other.token()).andExpect(jsonPath("$.length()").value(0));
        api.call(get("/sales/"+unpaid+"/progress"),other.token()).andExpect(status().isNotFound());
        api.call(json(post("/sales/"+unpaid+"/cancel"),cancelBody(true,"other")),other.token()).andExpect(status().isNotFound());
        String cashier=api.member(owner,"CASHIER");
        api.call(json(post("/sales/"+unpaid+"/cancel"),cancelBody(true,"cashier")),cashier).andExpect(status().isForbidden());
        api.call(json(post("/sales/"+unpaid+"/progress"),"{\"progress\":\"CLOSED\",\"reason\":\"Delivered\"}"),cashier).andExpect(status().isOk());
        String packer=api.member(owner,"PACKER");
        api.call(get("/sales/"+unpaid+"/progress"),packer).andExpect(status().isForbidden());
    }

    @Test void finalRefundIncludesPositiveAndNegativeReceiptRoundingExactly() throws Exception {
        api.call(json(patch("/organization"),"{\"roundTotalToNearest\":100}"),owner.token()).andExpect(status().isOk());
        for (int price : new int[]{990,1010}) {
            api.call(json(patch("/products/"+product),"{\"retailPrice\":"+price+"}"),owner.token()).andExpect(status().isOk());
            String id=sale("ONLINE",1,"[{\"method\":\"KBZ_PAY\"}]");
            var result=api.call(json(post("/sales/"+id+"/cancel"),cancelBody(true,"round-"+price)),owner.token()).andExpect(status().isOk());
            api.call(get("/returns/"+read(result,"$.returnId")),owner.token())
                .andExpect(jsonPath("$.refundAmount").value(1000))
                .andExpect(jsonPath("$.roundingRefundAmount").value(1000-price));
            state(id,"CANCELED","REFUNDED",1000,1000,0);
        }
    }

    @Test void freeSalesCanBeCanceledWithoutInventingPaymentOrNeedingAReceivable() throws Exception {
        api.call(json(patch("/products/"+product),"{\"retailPrice\":0}"),owner.token()).andExpect(status().isOk());
        String id=sale("ONLINE",1,"[{\"method\":\"CASH\"}]");
        state(id,"OPEN","PAID",0,0,0);
        api.call(json(post("/sales/"+id+"/cancel"),cancelBody(true,"free")),owner.token()).andExpect(status().isOk());
        state(id,"CANCELED","PAID",0,0,0);
    }

    @Test void writingOffDebtDoesNotCountAsPaymentAndCannotBeSilentlyCanceled() throws Exception {
        String id=deposit();
        String receivable=read(api.call(get("/sales/"+id),owner.token()),"$.paymentState.receivableId");
        api.call(json(post("/receivables/"+receivable+"/write-off"),"{\"reason\":\"Uncollectable\"}"),owner.token()).andExpect(status().isOk());
        state(id,"OPEN","DEPOSIT",1000,0,0);
        api.call(get("/sales/"+id),owner.token()).andExpect(jsonPath("$.paymentState.writtenOffAmount").value(2000));
        api.call(json(post("/sales/"+id+"/cancel"),cancelBody(true,"written-off")),owner.token())
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("sale_credit_written_off"));
        state(id,"OPEN","DEPOSIT",1000,0,0);
    }

    @Test void changedCancellationAmountsRequireAReviewAndPostNothing() throws Exception {
        String id=deposit();
        String body=cancelBody(true,"stale").replace("\"reason\":", "\"expectedNetReceivedAmount\":999,\"expectedOutstandingAmount\":2000,\"reason\":");
        api.call(json(post("/sales/"+id+"/cancel"),body),owner.token()).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("cancellation_changed"));
        state(id,"OPEN","DEPOSIT",1000,0,2000);
        api.call(get("/stock-balances?productId="+product),owner.token()).andExpect(jsonPath("$[0].quantity").value(17));
    }
}
