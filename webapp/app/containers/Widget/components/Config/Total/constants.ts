import { ViewModelVisualTypes } from 'containers/View/constants'

export enum TotalTypes {
  RowTotal = 'rowTotal',
  ColTotal = 'colTotal',
  RowSubTotal = 'rowSubTotal',
  ColSubTotal = 'colSubTotal'
}

export const TotalTypesSetting = {
  [ViewModelVisualTypes.Number]: [
    TotalTypes.RowTotal,
    TotalTypes.ColTotal,
    TotalTypes.RowSubTotal,
    TotalTypes.ColSubTotal
  ]
}

export const TotalTypesLocale = {
  [TotalTypes.RowTotal]: '行总计',
  [TotalTypes.ColTotal]: '列总计',
  [TotalTypes.RowSubTotal]: '行小计',
  [TotalTypes.ColSubTotal]: '列小计'
}
