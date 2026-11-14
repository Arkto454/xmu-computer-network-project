const { createScaffold } = require('../../utils/scaffold')
const { listPublicExamples } = require('../../config/demo')
Page(createScaffold({data: {tasks: []}, load() { this.setData({tasks: this.data.isMock ? listPublicExamples() : []}) }}))
