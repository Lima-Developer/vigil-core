package com.vigil.application.port.out.interfaces;

public record StorageFacts(boolean directoryExists, boolean directoryWritable, boolean fileWritable,
                           long usableBytes, String integrityResult, String failure) {
}
