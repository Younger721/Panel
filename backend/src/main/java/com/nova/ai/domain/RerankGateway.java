package com.nova.ai.domain; import java.util.List; public interface RerankGateway { List<RankedChunk> rerank(String query,List<ContextSegment> candidates); }
