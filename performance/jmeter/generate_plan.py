"""Generates apg-load.jmx. Run: python performance/jmeter/generate_plan.py  (writes next to this file).

Scenarios (each a thread group; sizes come from -Jthreads / -Jduration / -Jrampup at run time):
  orders      create orders with the merchant API key (idempotency key per request)             expect 201
  payments    create an order, then a payment for it                                            expect 201, 201
  reads       list orders, list payments, fetch one order                                      expect 200
  dashboard   log in (bcrypt) and read the ledger balance with the JWT                          expect 200, 200  (low weight)
  forged      POST bogus provider callbacks: exercises hash rejection only, never calls PayU    expect 400
There is deliberately NO checkout / verify step: nothing here may reach the real provider.
"""
import os
import xml.sax.saxutils as sx

HEADERS_JSON = [("Content-Type", "application/json")]


def esc(s):
    return sx.escape(s, {'"': "&quot;"})


def prop(kind, name, value):
    return f'<{kind} name="{esc(name)}">{esc(str(value))}</{kind}>'


def header_manager(headers):
    items = "".join(
        f'<elementProp name="" elementType="Header">{prop("stringProp", "Header.name", n)}{prop("stringProp", "Header.value", v)}</elementProp>'
        for n, v in headers)
    return ('<HeaderManager guiclass="HeaderPanel" testclass="HeaderManager" testname="Headers" enabled="true">'
            f'<collectionProp name="HeaderManager.headers">{items}</collectionProp></HeaderManager><hashTree/>')


def assertion(name, codes):
    return ('<ResponseAssertion guiclass="AssertionGui" testclass="ResponseAssertion" testname="' + esc(name) + '" enabled="true">'
            f'<collectionProp name="Asserion.test_strings">{"".join(prop("stringProp", str(abs(hash(c))), c) for c in codes)}</collectionProp>'
            + prop("stringProp", "Assertion.custom_message", "") + prop("stringProp", "Assertion.test_field", "Assertion.response_code")
            + prop("boolProp", "Assertion.assume_success", "false") + prop("intProp", "Assertion.test_type", "8")   # 8 = equals, OR across strings
            + '</ResponseAssertion><hashTree/>')


def json_extractor(var, path):
    return ('<JSONPostProcessor guiclass="JSONPostProcessorGui" testclass="JSONPostProcessor" testname="extract ' + var + '" enabled="true">'
            + prop("stringProp", "JSONPostProcessor.referenceNames", var) + prop("stringProp", "JSONPostProcessor.jsonPathExprs", path)
            + prop("stringProp", "JSONPostProcessor.match_numbers", "1") + prop("stringProp", "JSONPostProcessor.defaultValues", "NOT_FOUND")
            + '</JSONPostProcessor><hashTree/>')


def sampler(name, method, path, body=None, headers=None, codes=("200",), extract=None, form=False):
    args = ""
    if body is not None:
        args = ('<elementProp name="HTTPsampler.Arguments" elementType="Arguments"><collectionProp name="Arguments.arguments">'
                '<elementProp name="" elementType="HTTPArgument">' + prop("boolProp", "HTTPArgument.always_encode", "false")
                + prop("stringProp", "Argument.value", body) + prop("stringProp", "Argument.metadata", "=") + '</elementProp></collectionProp></elementProp>')
    else:
        args = '<elementProp name="HTTPsampler.Arguments" elementType="Arguments"><collectionProp name="Arguments.arguments"/></elementProp>'
    s = ('<HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="' + esc(name) + '" enabled="true">' + args
         + prop("stringProp", "HTTPSampler.path", path) + prop("stringProp", "HTTPSampler.method", method)
         + prop("boolProp", "HTTPSampler.follow_redirects", "false") + prop("boolProp", "HTTPSampler.use_keepalive", "true")
         + prop("boolProp", "HTTPSampler.postBodyRaw", "true" if body is not None else "false")
         + '</HTTPSamplerProxy><hashTree>')
    if headers:
        s += header_manager(headers)
    s += assertion(name + " status", codes)
    if extract:
        s += json_extractor(*extract)
    return s + '</hashTree>'


def thread_group(name, threads_expr, samplers):
    return ('<ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="' + esc(name) + '" enabled="true">'
            + prop("stringProp", "ThreadGroup.on_sample_error", "continue")
            + '<elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController">'
            + prop("boolProp", "LoopController.continue_forever", "false") + prop("intProp", "LoopController.loops", "-1") + '</elementProp>'
            + prop("stringProp", "ThreadGroup.num_threads", threads_expr) + prop("stringProp", "ThreadGroup.ramp_time", "${__P(rampup,10)}")
            + prop("boolProp", "ThreadGroup.scheduler", "true") + prop("stringProp", "ThreadGroup.duration", "${__P(duration,60)}")
            + prop("stringProp", "ThreadGroup.delay", "0") + '</ThreadGroup><hashTree>' + "".join(samplers) + '</hashTree>')


AUTH_KEY = [("Authorization", "Bearer ${apiKey}")]
JSON_KEY = HEADERS_JSON + AUTH_KEY


def build():
    orders = [sampler("create order", "POST", "/api/v1/orders",
                      '{"amount":${__Random(100,100000)},"currency":"INR","description":"load test","customer":{"name":"Load Tester","email":"load@example.com"}}',
                      JSON_KEY + [("Idempotency-Key", "o-${__UUID()}")], ("201",))]
    payments = [
        sampler("create order (for payment)", "POST", "/api/v1/orders", '{"amount":${__Random(100,100000)},"currency":"INR","description":"load test payment"}',
                JSON_KEY + [("Idempotency-Key", "po-${__UUID()}")], ("201",), extract=("orderId", "$.data.id")),
        sampler("create payment", "POST", "/api/v1/payments", '{"orderId":"${orderId}"}', JSON_KEY + [("Idempotency-Key", "p-${__UUID()}")], ("201",)),
    ]
    reads = [
        sampler("create order (for reads)", "POST", "/api/v1/orders", '{"amount":2500,"currency":"INR"}', JSON_KEY + [("Idempotency-Key", "ro-${__UUID()}")],
                ("201",), extract=("readOrderId", "$.data.id")),
        sampler("get order", "GET", "/api/v1/orders/${readOrderId}", None, AUTH_KEY, ("200",)),
        sampler("list orders", "GET", "/api/v1/orders?size=20", None, AUTH_KEY, ("200",)),
        sampler("list payments", "GET", "/api/v1/payments?size=20", None, AUTH_KEY, ("200",)),
    ]
    dashboard = [
        sampler("login", "POST", "/api/v1/auth/login", '{"email":"${email}","password":"${password}"}', HEADERS_JSON, ("200",), extract=("jwt", "$.data.accessToken")),
        sampler("ledger balance", "GET", "/api/v1/ledger/balance", None, [("Authorization", "Bearer ${jwt}")], ("200",)),
        sampler("webhook deliveries", "GET", "/api/v1/webhook-deliveries?size=20", None, [("Authorization", "Bearer ${jwt}")], ("200",)),
    ]
    forged = [sampler("forged payu callback", "POST", "/api/v1/webhooks/payu",
                      "txnid=A${__RandomString(12,abcdef0123456789)}&mihpayid=1&status=success&unmappedstatus=captured&amount=1.00&key=forged&hash=${__RandomString(128,abcdef0123456789)}",
                      [("Content-Type", "application/x-www-form-urlencoded"), ("Accept", "text/html")], ("400",))]

    csv = ('<CSVDataSet guiclass="TestBeanGUI" testclass="CSVDataSet" testname="credentials" enabled="true">'
           + prop("stringProp", "filename", "${__P(creds,/data/credentials.csv)}") + prop("stringProp", "variableNames", "apiKey,email,password")
           + prop("boolProp", "ignoreFirstLine", "true") + prop("stringProp", "delimiter", ",") + prop("boolProp", "recycle", "true")
           + prop("boolProp", "stopThread", "false") + prop("stringProp", "shareMode", "shareMode.all") + '</CSVDataSet><hashTree/>')
    defaults = ('<ConfigTestElement guiclass="HttpDefaultsGui" testclass="ConfigTestElement" testname="gateway" enabled="true">'
                '<elementProp name="HTTPsampler.Arguments" elementType="Arguments" guiclass="HTTPArgumentsPanel" testclass="Arguments"><collectionProp name="Arguments.arguments"/></elementProp>'
                + prop("stringProp", "HTTPSampler.domain", "${__P(host,localhost)}") + prop("stringProp", "HTTPSampler.port", "${__P(port,8080)}")
                + prop("stringProp", "HTTPSampler.protocol", "http") + prop("stringProp", "HTTPSampler.connect_timeout", "5000")
                + prop("stringProp", "HTTPSampler.response_timeout", "15000") + '</ConfigTestElement><hashTree/>')

    groups = (thread_group("orders", "${__P(threads,20)}", orders)
              + thread_group("payments", "${__P(threads,20)}", payments)
              + thread_group("reads", "${__P(threads,20)}", reads)
              + thread_group("dashboard (login + reads)", "${__P(dashThreads,2)}", dashboard)
              + thread_group("forged callbacks", "${__P(forgedThreads,2)}", forged))

    return ('<?xml version="1.0" encoding="UTF-8"?>\n<jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3"><hashTree>'
            '<TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="APG load" enabled="true">'
            + prop("boolProp", "TestPlan.functional_mode", "false") + prop("boolProp", "TestPlan.serialize_threadgroups", "false")
            + '<elementProp name="TestPlan.user_defined_variables" elementType="Arguments" guiclass="ArgumentsPanel" testclass="Arguments"><collectionProp name="Arguments.arguments"/></elementProp>'
            + '</TestPlan><hashTree>' + defaults + csv + groups + '</hashTree></hashTree></jmeterTestPlan>\n')


if __name__ == "__main__":
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "apg-load.jmx")
    open(out, "w", encoding="utf-8").write(build())
    print("wrote", out)
