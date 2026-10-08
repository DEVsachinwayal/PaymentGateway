# PayU integration notes (from docs.payu.in, researched 2026-10-09)
Endpoints: test `https://test.payu.in/_payment` (checkout POST), `https://test.payu.in/merchant/postservice?form=2` (S2S APIs). Live: `https://secure.payu.in/_payment`. Credentials: .env `PAYU_MERCHANT_KEY`, `PAYU_MERCHANT_SALT` (use the 32-char salt v1), `PAYU_BASE_URL`. Never commit; never log.

## Payment request (hosted checkout, browser form POST)
Fields: key, txnid (unique per attempt), amount ("499.00" 2 decimals), productinfo, firstname, email, phone, surl, furl, [curl], udf1-5, hash.
`hash = sha512(key|txnid|amount|productinfo|firstname|email|udf1|udf2|udf3|udf4|udf5||||||SALT)` (empty udf keep their pipes; six pipes before SALT). Hex lowercase, server side only.

## Response hash (verify BEFORE trusting surl/furl callback)
`sha512(SALT|status||||||udf5|udf4|udf3|udf2|udf1|email|firstname|productinfo|amount|txnid|key)`; with additional charges: prefix `additional_charges|`. Also compare txnid and amount with our own record. status success/failure/pending (only success is success; pending => verify).

## verify_payment (S2S)
POST form: key, command=verify_payment, var1=txnid, hash=sha512(key|verify_payment|var1|SALT). Response `{status:1,msg,transaction_details:{<txnid>:{mihpayid,status,unmappedstatus,amt,mode,error_code,error_Message,bank_ref_num,...}}}`; unknown txn => status 0 / "Not Found". unmappedstatus: captured | failed | usercancelled | pending.

## Refund (S2S)
command=cancel_refund_transaction, var1=mihpayid, var2=unique refund token (<=23 chars), var3=amount "10.00", hash=sha512(key|cancel_refund_transaction|var1|SALT). status 1 = "Refund Request Queued" (+request id); poll refund status API.

## Test instruments (docs.payu.in "Test Cards, UPI ID and Wallets")
Net banking user/pass payu/payu OTP 123456; test VPAs anything@payu or 999999999@payu (UPI collect simulator https://pgsim01.payu.in/UPI-test-transaction/confirm/<txnid>); MasterCard 5123456789012346 CVV 123 OTP 123456 any future expiry; PayTM 7777777777 OTP 888888.

## Status 2026-10-09
Credentials in .env rejected with "Invalid Hash." – see WORK_LOG. Not yet verified against the live sandbox.
