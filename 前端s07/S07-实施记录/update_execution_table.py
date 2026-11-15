from copy import copy
from pathlib import Path

import openpyxl
from openpyxl.workbook.properties import CalcProperties


root = Path(__file__).resolve().parents[1]
path = root / '前端开发执行表.xlsx'
workbook = openpyxl.load_workbook(path)

plan = workbook['任务计划']
assert plan['A8'].value == 'S07'
plan['F8'] = '验收通过'
plan['I8'] = 'Mock受控场景；无真实网络'
plan['L8'] = (
    '固定虚构账号A/B/C、五类任务和六种状态；公开字段白名单及关系私密信息隔离完成；'
    '发布、接单、取消、取件、送达、确认与模拟结算可改变状态；7组受控检查及33个JS语法检查通过。'
)
plan['M8'] = '页面尚未切换业务服务；真实登录、后端联调、二维码渲染及真实结算未执行。'
plan['N8'] = '下一步S08：核对首批接口的实际方法、路径、权限和恢复语义。'
plan['O8'] = (
    'C:/Users/23997/Documents/Codex/2026-10-23/zhe/outputs/S07-实施记录/实施说明.md；'
    '验收记录.md；自动检查.json；改动清单.json'
)
plan['P8'] = '2026-11-15'

start = workbook['开始这里']
start['B2'] = '按S08锁定首批接口、权限及请求结果恢复契约，真实接口未定时继续Mock。'
start['C2'] = 'S01—S07已验收；Mock通过不等于真实后端联调。下一任务S08。'
start['C4'] = 'S01—S07已验收。默认Mock；真实登录和业务尚未接入。'

log = workbook['本次记录']
values = [
    '2026-11-15', 'S07', '固定Mock数据与异常场景',
    'S07任务卡；fixtures/service/scenarios；受控Node检查',
    'A/B/C、任务状态与私密信息隔离通过；发布到模拟结算状态流转通过；失败/延迟/待确认/码过期可重置。',
    'S07-实施记录/实施说明.md；验收记录.md；自动检查.json；S07快照',
    '页面服务接入和真实后端联调未执行，不把Mock标作真实验证。',
    '按S08核对已实现接口及恢复契约。', 'S07验收通过；S08待开始'
]
row = None
for existing_row in range(2, log.max_row + 1):
    if log.cell(existing_row, 1).value == '2026-11-15' and log.cell(existing_row, 2).value == 'S07':
        row = existing_row
        break
if row is None:
    row = log.max_row + 1
for column, value in enumerate(values, 1):
    cell = log.cell(row, column, value)
    source = log.cell(row - 1, column)
    if source.has_style:
        cell._style = copy(source._style)
    if source.number_format:
        cell.number_format = source.number_format
    if source.alignment:
        cell.alignment = copy(source.alignment)
    if source.protection:
        cell.protection = copy(source.protection)
log.row_dimensions[row].height = log.row_dimensions[row - 1].height
log.auto_filter.ref = f'A1:I{max(row, 8)}'

workbook.calculation = CalcProperties(calcMode='auto', fullCalcOnLoad=True, forceFullCalc=True)
workbook.save(path)

check = openpyxl.load_workbook(path, data_only=False)
assert check['任务计划']['F8'].value == '验收通过'
assert check['开始这里']['B2'].value.startswith('按S08')
assert check['本次记录'].max_row == max(row, 8)
assert check['开始这里']['C9'].value.startswith('=COUNTIF(')
assert len(check.worksheets) == 7
print({'updatedTask': check['任务计划']['A8'].value, 'recordRow': row, 'sheets': len(check.worksheets)})
