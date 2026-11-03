package com.disputepilot.domain;

/** A reference to a source record, e.g. id "shipment:SHP-77", kind "shipment". */
public record Citation(String id, String kind, String label) {
}
