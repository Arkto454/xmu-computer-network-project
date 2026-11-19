Component({
  properties: { task: { type: Object, value: null } },
  methods: {
    open() {
      const task = this.properties.task
      if (task && typeof task.id === 'string' && task.id) this.triggerEvent('open', { taskId: task.id })
    }
  }
})
