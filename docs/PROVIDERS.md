# Providers
Port: `PaymentProvider` (provider-integration). Adding a provider = new package implementing the port + `@ConditionalOnProperty` + config row; no change in payment/orchestrator code.
| Provider | Status | Notes |
|---|---|---|
| payu | sandbox verified (signature accepted by test.payu.in) | hosted checkout, verify_payment, refund; see PAYU_INTEGRATION.md |
Deviation from spec 9: `getPaymentStatus` takes OUR provider reference (PayU `txnid`) because that is PayU's lookup key.
