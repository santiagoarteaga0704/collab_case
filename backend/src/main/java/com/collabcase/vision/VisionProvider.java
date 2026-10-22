package com.collabcase.vision;

public interface VisionProvider {
    VisionProviderResponse extract(VisionProviderRequest request);
}
