/**
 * 上传接口（后端 UploadController）。
 * 只接受 JPG / PNG / WebP，单张 ≤ 2MB；返回相对路径 `/uploads/xxx.webp`。
 */
import http from './http'

export default {
  /**
   * 上传选项配图。
   * 必须带 surveyId：后端按「作者 / 有编辑权的协作者」校验，防止登录即可传图变成免费图床。
   * 文件走 multipart，http.js 会自动识别 FormData 并交给浏览器设置 boundary。
   */
  optionImage: (surveyId, file) => {
    const form = new FormData()
    form.append('file', file)
    return http.post(`/api/upload/option-image?surveyId=${surveyId}`, form)
  }
}
