package com.apg.common.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;

/** Logback conversion word {@code %masked}: the formatted message plus stack trace, with secrets masked. */
public class MaskingConverter extends ClassicConverter {
    @Override
    public String convert(ILoggingEvent event) {
        String msg = LogMasker.mask(event.getFormattedMessage());
        IThrowableProxy t = event.getThrowableProxy();
        if (t == null) return msg;
        return msg + System.lineSeparator() + LogMasker.mask(ThrowableProxyUtil.asString(t));
    }
}
