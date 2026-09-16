<template>
  <div style="padding: 20px; max-width: 900px; margin: 0 auto;">
    <!-- 顶部操作栏 -->
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;">
      <h2 style="margin: 0;">📝 问卷编辑器 (ID: {{ surveyId }})</h2>
      <div>
        <el-button @click="goBack">返回</el-button>
        <el-button type="primary" @click="saveQuestions">保存</el-button>
        <el-button
          v-if="surveyInfo.status === 0"
          type="success"
          @click="publishSurvey"
        >立即发布</el-button>
        <el-tag v-else-if="surveyInfo.status === 1" type="success" style="margin-left: 10px;">已发布（可继续修改）</el-tag>
        <el-tag v-else-if="surveyInfo.status === 2" type="danger" style="margin-left: 10px;">已结束</el-tag>
      </div>
    </div>

    <!-- 问卷基础信息 -->
    <el-card style="margin-bottom: 20px;">
      <el-form label-width="100px">
        <el-form-item label="问卷标题">
          <el-input v-model="surveyInfo.title" placeholder="请输入问卷标题" />
        </el-form-item>
        <el-form-item label="问卷描述">
          <el-input v-model="surveyInfo.description" type="textarea" :rows="2" placeholder="请输入问卷描述" />
        </el-form-item>
        <el-form-item label="定时发布">
          <el-date-picker
            v-model="surveyInfo.startTime"
            type="datetime"
            placeholder="不设置则手动发布"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%;"
          />
        </el-form-item>
        <el-form-item label="截止时间">
          <el-date-picker
            v-model="surveyInfo.endTime"
            type="datetime"
            placeholder="不设置则永不过期"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%;"
          />
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 题目列表 -->
    <div v-if="questions.length === 0" style="text-align: center; color: #999; padding: 40px 0; background: #fff; border-radius: 8px;">
      还没有题目，点击下方按钮添加第一道题
    </div>

    <el-card v-for="(q, qIndex) in questions" :key="qIndex" style="margin-bottom: 16px;">
      <div style="display: flex; justify-content: space-between; align-items: flex-start;">
        <div style="flex: 1;">
          <el-tag :type="q.type === 1 ? 'primary' : q.type === 2 ? 'success' : 'warning'" style="margin-bottom: 8px;">
            {{ q.type === 1 ? '单选题' : q.type === 2 ? '多选题' : '单行输入' }}
          </el-tag>
          <el-checkbox v-model="q.required" :true-value="1" :false-value="0" style="margin-left: 10px;">必填</el-checkbox>

          <el-input v-model="q.title" placeholder="请输入题目内容" style="margin-top: 10px;" />

          <!-- 选项区域 -->
          <div v-if="q.type === 1 || q.type === 2" style="margin-top: 12px; padding-left: 20px;">
            <div v-for="(opt, oIndex) in q.options" :key="oIndex" style="display: flex; align-items: center; margin-bottom: 8px;">
              <el-icon style="margin-right: 8px; color: #999;">
                <component :is="q.type === 1 ? 'CircleCheck' : 'Select'" />
              </el-icon>
              <el-input v-model="opt.content" placeholder="请输入选项内容" style="width: 220px; margin-right: 8px;" />

              <!-- 跳转设置：单选和多选都支持 -->
              <el-select
                v-model="opt.nextQuestionId"
                placeholder="下一题"
                clearable
                size="small"
                style="width: 130px; margin-right: 8px;"
              >
                <el-option
                  v-for="(target, ti) in questions"
                  :key="ti"
                  :label="`跳到第 ${ti + 1} 题`"
                  :value="ti + 1"
                  :disabled="ti <= qIndex"
                />
                <el-option label="结束问卷" :value="-1" />
              </el-select>

              <el-button type="danger" link size="small" @click="removeOption(qIndex, oIndex)">删除</el-button>
            </div>

            <!-- 多选跳转规则提示 -->
            <div v-if="q.type === 2" style="font-size: 12px; color: #909399; margin-top: 4px; padding-left: 24px;">
              💡 多选跳转规则：勾选的多个选项中，按选项顺序取第一个命中的跳转
            </div>

            <el-button type="primary" link size="small" @click="addOption(qIndex)">+ 添加选项</el-button>
          </div>
        </div>

        <el-button type="danger" link style="margin-left: 10px;" @click="removeQuestion(qIndex)">删除题目</el-button>
      </div>
    </el-card>

    <!-- 底部添加题目 -->
    <div style="text-align: center; margin-top: 20px; padding: 20px; background: #fff; border-radius: 8px;">
      <span style="margin-right: 10px; color: #666;">添加题目：</span>
      <el-button type="primary" plain @click="addQuestion(1)">单选题</el-button>
      <el-button type="success" plain @click="addQuestion(2)">多选题</el-button>
      <el-button type="warning" plain @click="addQuestion(3)">单行输入</el-button>
    </div>
  </div>
</template>

<script>
import { ElMessage } from 'element-plus'

export default {
  data() {
    return {
      surveyId: null,
      surveyInfo: {
        title: '',
        description: '',
        status: 0,
        shareCode: null,
        startTime: null,
        endTime: null
      },
      questions: []
    }
  },
  mounted() {
    this.surveyId = this.$route.params.id
    if (this.surveyId) {
      this.loadSurveyInfo()
      this.loadQuestions()
    }
  },
  methods: {
    async loadSurveyInfo() {
      try {
        const res = await fetch(`/api/survey/${this.surveyId}`)
        const data = await res.json()
        this.surveyInfo = {
          title: data.title || '',
          description: data.description || '',
          status: data.status || 0,
          shareCode: data.shareCode,
          startTime: data.startTime,
          endTime: data.endTime
        }
      } catch {
        ElMessage.error('加载问卷信息失败')
      }
    },

    async loadQuestions() {
      try {
        const res = await fetch(`/api/question/list/${this.surveyId}`)
        const data = await res.json()
        this.questions = data.map(q => ({
          ...q,
          required: q.required || 0,
          options: q.options || []
        }))
      } catch {
        ElMessage.error('加载题目失败')
      }
    },

    addQuestion(type) {
      const newQ = { type, title: '', required: 0, options: [] }
      if (type === 1 || type === 2) {
        newQ.options = [{ content: '' }, { content: '' }]
      }
      this.questions.push(newQ)
    },

    removeQuestion(index) {
      this.questions.splice(index, 1)
    },

    addOption(qIndex) {
      this.questions[qIndex].options.push({ content: '' })
    },

    removeOption(qIndex, oIndex) {
      this.questions[qIndex].options.splice(oIndex, 1)
    },

    async saveQuestions() {
      if (!this.surveyInfo.title.trim()) {
        ElMessage.warning('请输入问卷标题')
        return
      }
      for (let i = 0; i < this.questions.length; i++) {
        if (!this.questions[i].title.trim()) {
          ElMessage.warning(`第 ${i + 1} 题的题目内容不能为空`)
          return
        }
      }

      try {
        // 1. 更新问卷信息（含时间字段）
        await fetch('/api/survey/update', {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            id: this.surveyId,
            title: this.surveyInfo.title,
            description: this.surveyInfo.description,
            status: this.surveyInfo.status,
            shareCode: this.surveyInfo.shareCode,
            startTime: this.surveyInfo.startTime,
            endTime: this.surveyInfo.endTime
          })
        })

        // 2. 保存题目
        const res = await fetch(`/api/question/save/${this.surveyId}`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(this.questions)
        })
        const msg = await res.text()
        if (msg === '保存成功') {
          ElMessage.success('保存成功')
        } else {
          ElMessage.error(msg)
        }
      } catch {
        ElMessage.error('保存失败')
      }
    },

    async publishSurvey() {
      await this.saveQuestions()
      try {
        const res = await fetch(`/api/survey/publish/${this.surveyId}`, { method: 'POST' })
        const data = await res.json()
        if (data && data.shareCode) {
          this.surveyInfo.status = 1
          this.surveyInfo.shareCode = data.shareCode
          ElMessage.success('发布成功！分享码：' + data.shareCode)
        } else {
          ElMessage.error('发布失败')
        }
      } catch {
        ElMessage.error('发布失败')
      }
    },

    goBack() {
      this.$router.push('/my/surveys')
    }
  }
}
</script>