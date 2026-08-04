package com.hamstrack.admin.dto;

/** A freshly generated one-time setup link ({@code /reset-password?token=}). */
public record SetupLinkResponse(String setupLink) {}
