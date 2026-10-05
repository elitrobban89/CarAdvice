package com.caradvice.service;

import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Periodslutet flyttade från prenumerationen till dess items i Stripe API 2025-03-31
 * (stripe-java 29+). Webhooks kan komma i båda formerna beroende på kontots API-version.
 *
 * @author Robert Andersson Kopler
 */
class StripeServicePeriodEndTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void periodslutLasesFranItemsINyaWebhooks() {
        var json = mapper.readTree("""
                {"customer":"cus_1","items":{"data":[{"current_period_end":1790000000}]}}""");
        assertThat(StripeService.periodEnd(json)).isEqualTo(1790000000L);
    }

    @Test
    void periodslutPaPrenumerationenFungerarForGamlaWebhooks() {
        var json = mapper.readTree("""
                {"customer":"cus_1","current_period_end":1780000000}""");
        assertThat(StripeService.periodEnd(json)).isEqualTo(1780000000L);
    }

    @Test
    void saknatPeriodslutGerNoll() {
        assertThat(StripeService.periodEnd(mapper.readTree("{\"customer\":\"cus_1\"}"))).isZero();
    }

    @Test
    void periodslutFranSdkObjektetTarSenasteItem() {
        var a = new SubscriptionItem();
        a.setCurrentPeriodEnd(1780000000L);
        var b = new SubscriptionItem();
        b.setCurrentPeriodEnd(1790000000L);
        var items = new SubscriptionItemCollection();
        items.setData(List.of(a, b));
        var sub = new Subscription();
        sub.setItems(items);
        assertThat(StripeService.periodEnd(sub)).isEqualTo(1790000000L);
    }

    @Test
    void prenumerationUtanItemsGerNull() {
        assertThat(StripeService.periodEnd(new Subscription())).isNull();
        assertThat(StripeService.periodEnd((Subscription) null)).isNull();
    }
}
