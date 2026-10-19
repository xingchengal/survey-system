/**
 * 协作者接口（后端 SurveyCollaboratorController）。
 * ★ 管理某份问卷的协作者限作者本人或管理员（后端判）；apply / leave / shared 只按当前登录用户。
 */
import http from './http'

export default {
  /** 某问卷的协作者列表 [{ userId, username, nickname, collaboratorRole }] */
  list: (surveyId) => http.get(`/api/survey/${surveyId}/collaborators`),

  /** 按用户名邀请：{ username, role: 'viewer'|'editor'|'analyst' } */
  invite: (surveyId, username, role) =>
    http.post(`/api/survey/${surveyId}/collaborators`, { username, role }),

  /** 改角色 */
  changeRole: (surveyId, userId, role) =>
    http.put(`/api/survey/${surveyId}/collaborators/${userId}`, { role }),

  /** 移除（只对已生效的协作者；待接受的邀请走 revoke） */
  remove: (surveyId, userId) =>
    http.del(`/api/survey/${surveyId}/collaborators/${userId}`),

  /** 撤销未接受的邀请（作者/管理员）；撤销后对方再点接受会 400 */
  revoke: (surveyId, userId) =>
    http.post(`/api/survey/${surveyId}/collaborators/${userId}/revoke`),

  /** 我的待接受邀请（只按当前登录用户） */
  invitations: () => http.get('/api/collaborator/invitations'),

  /** 接受邀请：PENDING → ACCEPTED，名额费在这一步扣 */
  accept: (invitationId) => http.post(`/api/collaborator/invitations/${invitationId}/accept`),

  /** 拒绝邀请：PENDING → REJECTED，不占名额、不扣费 */
  reject: (invitationId) => http.post(`/api/collaborator/invitations/${invitationId}/reject`),

  /** 退出协作（本人） */
  leave: (surveyId) => http.del(`/api/collaborator/self/${surveyId}`),

  /** 申请变更自己的角色（作者会收到通知，需作者同意） */
  apply: (surveyId, role) => http.post(`/api/collaborator/self/${surveyId}/apply`, { role }),

  /** 共享给我的问卷（只按当前用户） */
  shared: () => http.get('/api/collaborator/shared')
}
