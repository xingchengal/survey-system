/**
 * AI 生成问卷接口（后端 AiGenerateController，基础路径 /api/ai）。
 *
 * ★ 请求体字段名必须与后端一致：后端读的是 topic 和 extra，不是 prompt。
 *   写成 { prompt } 会让后端拿到 null topic、直接返回「请输入问卷主题」，功能静默失效（HTTP 200）。
 * 这是慢接口（后端调模型 read 超时 120 秒），页面上必须保留 loading，不要设短超时。
 * 契约：生成失败后端抛 BizException → 400 + message，前端只看状态码并按 message 展示。
 */
import http, { ApiError, notifyError } from './http'

export default {
  /**
   * 根据一段需求描述生成整份问卷。
   *
   * @param {string} topic 主题，如"大学生手机使用情况"
   * @param {string} [extra] 附加要求，如"10 题以内，包含多选题"（对应后端的 extra 字段）
   * @returns {Promise<{success: boolean, surveyId: number, message?: string}>}
   *          只有拿到 surveyId 才算成功 —— 页面要靠它跳转到编辑页
   */
  async generate(topic, extra) {
    const data = await http.post('/api/ai/generate', { topic, extra })

    // 双保险：没有 surveyId 说明结果不可用（页面拿不到 id 就无法跳编辑页，
    // 等于"点了没反应"），此时按失败处理并提示。
    if (!data || !data.surveyId) {
      const message = (data && data.message) || 'AI 未返回问卷，请重试'
      notifyError(message)
      throw new ApiError(400, message)
    }
    return data
  }
}
