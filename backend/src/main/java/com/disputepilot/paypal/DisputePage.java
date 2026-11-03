package com.disputepilot.paypal;

import java.util.List;

public record DisputePage(List<PayPalDispute> items, String nextPageToken) {
}
