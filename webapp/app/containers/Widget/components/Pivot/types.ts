
export type TreeNodeLevelType = 'row' | 'col' | 'metrics'

export type TreeNodeSumType =
  | 'default'
  | 'colTotal'
  | 'rowTotal'
  | 'subTotal'
  | 'rowSubTotal'
  | 'colSubTotal'

  export interface ITreeNodeProperty {
    initKey: string
    key: string
    label: string
    levelCount: number
    levelType: TreeNodeLevelType
    listNumber: number
    originKey: string
    originParentKey: string
    parentKey: string
    samePathNode: object
    sumType: TreeNodeSumType
    sumLastNode: Boolean
    sumNode: Boolean
  }
