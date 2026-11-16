import apiClient from './client'

export interface KbCatalogNode {
  id: number
  parentId: number
  name: string
  pathKey: string
  depth: number
  chunkCount: number
  questionCount: number
  system: boolean
  description: string | null
  children: KbCatalogNode[]
}

export interface KbCatalogItem {
  id: number
  itemType: 'CHUNK' | 'QUESTION'
  title: string
  excerpt: string
  documentId: number | null
  documentName: string | null
  ordinal: number | null
  pageNo: number | null
  questionId: number | null
  company: string | null
  year: number | null
  difficulty: string | null
  tags: string[]
  /** 挂在哪；null = 还没挂（只会出现在「未分类」） */
  categoryId: number | null
  /** 人工确认过 = 自动归类不再覆盖 */
  locked: boolean
  confidence: number | null
  /** LLM / RULE / MANUAL / MOVE / IMPORT */
  linkSource: string | null
}

export interface KbCatalogGroup {
  id: number
  name: string
  pathKey: string
  total: number
  items: KbCatalogItem[]
}

export interface KbCatalogPage {
  items: KbCatalogItem[]
  total: number
  offset: number
  limit: number
  groups: KbCatalogGroup[]
}

export interface CreateKbCategoryPayload {
  parentId?: number | null
  name: string
  description?: string | null
  pathKey?: string | null
}

/** 一次归类的结果，数字全部来自"跑前跑后差集"，连点两次不会重复计数。 */
export interface KbClassifyResult {
  /** 本次参与归类的条目数 */
  total: number
  /** 本次新挂上（或改挂）的 */
  assigned: number
  /** 本来就人工确认过、这次跳过的 */
  alreadyPinned: number
  /** 挂上了但没人确认（页面上叫「待确认」） */
  pending: number
  /** 没挂上、落进「未分类」的 */
  failed: number
  mode: 'LLM' | 'RULE' | 'NONE' | 'NO_CATEGORY'
  /** 本次先清掉的低置信挂载数（只有"按文档重跑"才有值） */
  cleared: number
  /** 后端拼好的人话，页面直接用 */
  summary: string
}

export const catalogApi = {
  tree(kbId: number): Promise<KbCatalogNode[]> {
    return apiClient.get<KbCatalogNode[], KbCatalogNode[]>(`/v1/kb/${kbId}/catalog/tree`)
  },
  create(kbId: number, payload: CreateKbCategoryPayload): Promise<unknown> {
    return apiClient.post<unknown, unknown>(`/v1/kb/${kbId}/catalog/categories`, payload)
  },
  renameCategory(
    categoryId: number,
    payload: { name: string; description?: string }
  ): Promise<unknown> {
    return apiClient.patch<unknown, unknown>(`/v1/kb/catalog/categories/${categoryId}`, payload)
  },
  /** 移动目录：parentId 换父、beforeId 决定同级顺序（null = 末尾）。 */
  moveCategory(
    categoryId: number,
    parentId: number | null,
    beforeId: number | null = null
  ): Promise<unknown> {
    return apiClient.post<unknown, unknown>(`/v1/kb/catalog/categories/${categoryId}/move`, {
      parentId,
      beforeId
    })
  },
  /** cascade=true 连子树一起删；子树上的题目/切片只摘挂载，回到"未分类"。 */
  deleteCategory(categoryId: number, cascade = false): Promise<unknown> {
    return apiClient.delete<unknown, unknown>(
      `/v1/kb/catalog/categories/${categoryId}?cascade=${cascade}`
    )
  },
  classify(kbId: number): Promise<KbClassifyResult> {
    return apiClient.post<KbClassifyResult, KbClassifyResult>(`/v1/kb/${kbId}/catalog/classify`)
  },
  /** 按文档一键归类：redoLowConfidence=true 会先清掉未锁定的低置信挂载再重跑。 */
  classifyDocument(
    kbId: number,
    documentId: number,
    redoLowConfidence = false
  ): Promise<KbClassifyResult> {
    return apiClient.post<KbClassifyResult, KbClassifyResult>(
      `/v1/kb/${kbId}/catalog/documents/${documentId}/classify?redoLowConfidence=${redoLowConfidence}`,
      {}
    )
  },
  /** 人工确认归属：锁定 + 置信度拉满，之后自动归类不会覆盖它。 */
  confirmItem(
    kbId: number,
    itemType: 'CHUNK' | 'QUESTION',
    itemId: number
  ): Promise<{ confirmed: number }> {
    return apiClient.post<{ confirmed: number }, { confirmed: number }>(
      `/v1/kb/${kbId}/catalog/items/${itemType}/${itemId}/confirm`
    )
  },
  /** 全库一键确认：把该类型所有"自动挂的、没确认过"的归属钉成人工真值。 */
  confirmAllItems(
    kbId: number,
    itemType: 'CHUNK' | 'QUESTION'
  ): Promise<{ confirmed: number; alreadyLocked: number }> {
    return apiClient.post<
      { confirmed: number; alreadyLocked: number },
      { confirmed: number; alreadyLocked: number }
    >(`/v1/kb/${kbId}/catalog/items/confirm-all?itemType=${itemType}`)
  },
  /** 从另一个库继承目录树（按 path_key 去重），用来给题库库补齐知识树。 */
  importTree(
    kbId: number,
    sourceKbId: number
  ): Promise<{ imported: number; skipped: number; total: number }> {
    return apiClient.post<
      { imported: number; skipped: number; total: number },
      { imported: number; skipped: number; total: number }
    >(`/v1/kb/${kbId}/catalog/import?sourceKbId=${sourceKbId}`)
  },
  moveItem(
    kbId: number,
    itemType: 'CHUNK' | 'QUESTION',
    itemId: number,
    categoryId: number | null
  ): Promise<unknown> {
    return apiClient.post<unknown, unknown>(`/v1/kb/${kbId}/catalog/items/move`, {
      itemType,
      itemId,
      categoryId
    })
  },
  items(
    kbId: number,
    categoryId: number,
    type: 'CHUNK' | 'QUESTION',
    offset = 0,
    limit = 20
  ): Promise<KbCatalogPage> {
    const params = new URLSearchParams({
      kbId: String(kbId),
      type,
      offset: String(offset),
      limit: String(limit)
    })
    return apiClient.get<KbCatalogPage, KbCatalogPage>(
      `/v1/kb/catalog/categories/${categoryId}/items?${params.toString()}`
    )
  }
}
