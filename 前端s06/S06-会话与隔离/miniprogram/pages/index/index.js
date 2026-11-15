const { createScaffold } = require('../../utils/scaffold')
const { listPublicExamples } = require('../../config/demo')
const navigation = require('../../utils/navigation')
const { createUiPreview } = require('../../config/ui-preview')
Page(createScaffold({
  data: { tasks: [], uiPreview: null, previewState: 'error', previewRetries: 0 },
  load(query) {
    this.setData({ tasks: this.data.isMock ? listPublicExamples() : [],
      uiPreview: this.data.isMock && query.uiPreview === 'all' ? createUiPreview() : null })
  },
  methods: {
    openTask(event) { navigation.open('detail', event.detail.taskId) },
    retryPreview() {
      if (!this.data.isMock || !this.data.uiPreview) return
      this.setData({ previewState: 'empty', previewRetries: this.data.previewRetries + 1 })
    }
  }
}))
