package org.gettserver.gettcore.editor;

import java.util.List;

/** Server-side reference data sent to the editor (server to client only). */
public record EditorRefs(List<AdvRef> advancements, List<String> missingItems, List<String> missingEntities) {
    public record AdvRef(String id, String title) {}
}
