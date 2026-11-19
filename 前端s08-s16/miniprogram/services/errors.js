// 只保留诊断所需元数据，不携带响应正文、联系方式或凭据。
class ServiceError extends Error {
  constructor(kind, message, metadata) {
    super(message)
    this.name = 'ServiceError'
    this.kind = kind
    const meta = metadata || {}
    this.httpStatus = meta.httpStatus == null ? null : meta.httpStatus
    this.businessCode = meta.businessCode == null ? null : meta.businessCode
    this.requestId = meta.requestId == null ? null : meta.requestId
    this.outcome = meta.outcome || 'not_sent'
    this.resultUnknown = this.outcome === 'unknown'
  }
}
module.exports = { ServiceError }
