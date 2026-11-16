/** 登录态里的"当前用户"：角色与权限用于前端隐藏入口/按钮，后端仍会再校验一次。 */
export interface Actor {
  userId: number
  tenantId: number | null
  username: string
  displayName: string | null
  email: string | null
  roles: string[]
  permissions: string[]
}

const ACTOR_KEY = 'panel.actor'

export function setActor(actor: Actor) {
  localStorage.setItem(ACTOR_KEY, JSON.stringify(actor))
}

export function getActor(): Actor | null {
  const raw = localStorage.getItem(ACTOR_KEY)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as Actor
    if (!parsed || typeof parsed.userId !== 'number') return null
    return {
      ...parsed,
      roles: Array.isArray(parsed.roles) ? parsed.roles : [],
      permissions: Array.isArray(parsed.permissions) ? parsed.permissions : []
    }
  } catch {
    return null
  }
}

export function clearActor() {
  localStorage.removeItem(ACTOR_KEY)
}

export function hasPermission(permission: string): boolean {
  const actor = getActor()
  if (!actor) return false
  if (actor.roles.includes('PLATFORM_ADMIN')) return true
  return actor.permissions.includes(permission)
}
