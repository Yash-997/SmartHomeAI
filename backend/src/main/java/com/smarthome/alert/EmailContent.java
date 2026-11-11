package com.smarthome.alert;

/** One rendered email: subject, plain-text part and HTML part (see {@link AlertEmailTemplates}). */
public record EmailContent(String subject, String text, String html) {
}
