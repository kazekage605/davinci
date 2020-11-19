import { SettingTypes, ItemTypes, ISettingItem } from './types'

const Total: ISettingItem = {
  key: 'total',
  name: '总计设置',
  constrants: [{
    settingType: SettingTypes.Dimension | SettingTypes.Indicator | SettingTypes.Color | SettingTypes.Tip | SettingTypes.Total,
    itemType: ItemTypes.Value,
    itemValueType: null
  }],
  sub: false,
  items: [{
    total: '总计设置'
  }]
}

export default Total
