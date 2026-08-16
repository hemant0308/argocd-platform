package com.argocd.platform.api.model.request.argocd;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request body sent by ArgoCD to the ApplicationSet Plugin Generator endpoint.
 * ArgoCD always POSTs to {@code POST /api/v1/getparams.execute} with this structure.
 *
 * <p>The {@code input.parameters} map contains all parameters declared in the
 * ApplicationSet generator block, including the {@code resource} key used for dispatch.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PluginGeneratorRequest {

    /** Name of the ApplicationSet that triggered this request. */
    private String applicationSetName;

    private Input input;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Input {
        /**
         * Parameters from the ApplicationSet generator block.
         * All values are strings per the ArgoCD Plugin Generator protocol.
         * Expected keys include {@code resource} (dispatch key) plus any
         * resource-specific parameters such as {@code partitionNumber}.
         */
        private Map<String, String> parameters;
    }
}
