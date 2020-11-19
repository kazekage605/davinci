import { TotalTypes } from './constants'

export interface IFieldTotalConfig {
  totalType: string[]
  [TotalTypes.RowTotal]?: string[]
  [TotalTypes.ColTotal]?: string[]
  [TotalTypes.RowSubTotal]?: string[]
  [TotalTypes.ColSubTotal]?: string[]
}
