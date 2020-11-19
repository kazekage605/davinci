import Node from './node'
import cloneDeep from 'lodash/cloneDeep'
import isEqual from 'lodash/isEqual'
import { getOriginKey, isSumLastNode, isSumNodeEnd } from './util'
import { SumType, CategoryType, SumText } from './constants'
import { removeRowColPrx } from './util'

export type TreeNodeLevelType = 'row' | 'col' | 'metrics'
export type TreeNodeSumType = Array<
  | 'default'
  | 'colTotal'
  | 'rowTotal'
  | 'subTotal'
  | 'rowSubTotal'
  | 'colSubTotal'
>
interface ITreeNodeProperty {
  initKey: string
  key: string
  label: string
  levelKey: string
  levelCount: number
  levelType: TreeNodeLevelType
  listNumber: number
  originKey: string
  originParentKey: string
  parentKey: string
  samePathNode: any
  sumType: TreeNodeSumType
  sumLastNode: boolean
  sumNode: boolean
  children: Array<ITreeNodeProperty>
  parent: ITreeNodeProperty
}

class MultiwayTree {
  public treeProps = {
    root: {
      rootTree: null,
      rootKey: 'name_level0_rows',
      rootLevel: { name_level0_rows: 'root' }
    },
    col: {
      colArray: [],
      colLast: null
    },
    row: {
      rowArray: [],
      rowLast: null,
      rootRowArray: []
    },
    metrics: {
      metricsText: [],
      metricsAgg: [],
      metricsSelect: {},
      metricsNodeList: [],
      metricsName: ['指标名称_cols', '指标名称_rows'],
      metricsNameCol: ['指标名称_cols']
    },
    color: {
      colorArray: []
    },
    list: {
      initWideList: [],
      totalWideList: [],
      keyOrder: []
    }
  }

  constructor() {}
  public getTotalWideTableJson() {
    let {
      metrics: { metricsNodeList, metricsText, metricsName },
      list: { totalWideList, keyOrder },
      color: { colorArray }
    } = this.treeProps
    metricsNodeList.forEach((item, number) => {
      if (!(number % metricsText.length)) {
        let wideObj = {}
        while (item.parent) {
          if (!metricsName.includes(item.originKey)) {
            if (this.getLevelType(item, 'Metrics')) {
              wideObj[item.originKey] = item[item.originKey]
            } else {
              wideObj[item.originKey] = item.label
            }
          }
          item = item.parent
        }
        totalWideList.push(wideObj)
      } else {
        let lastListItem = totalWideList[totalWideList.length - 1]
        lastListItem[item.originKey] = item[item.originKey]
        keyOrder = keyOrder.filter((key) => !metricsName.includes(key))
        keyOrder.forEach((key) => {
          const value = Reflect.get(lastListItem, key)
          Reflect.deleteProperty(lastListItem, key)
          lastListItem[key] = value
        })
      }
    })
    console.log(totalWideList, 'totalWideList')
    totalWideList = totalWideList.reduce((list, item) => {
      const equalItem = list.find((obj) => {
        if (colorArray.length) {
          const cloneOrigin = cloneDeep(item)
          const cloneObj = cloneDeep(obj)
          Reflect.deleteProperty(cloneOrigin, colorArray[0])
          Reflect.deleteProperty(cloneObj, colorArray[0])
          return isEqual(cloneObj, cloneOrigin)
        } else {
          return isEqual(obj, item)
        }
      })
      return (list = equalItem ? [...list] : [...list, item])
    }, [])
    return totalWideList
  }
  public getInitProps(props) {
    const { rows, cols, metrics, data, color } = props
    const colorItem = [...rows, ...cols].some(
      (item) => item.name === color.items[0]?.name
    )
    let rowsGroup = [...rows]
    if (!colorItem) {
      rowsGroup.push(...color.items)
    }
    this.treeProps.color.colorArray = [`${color.items[0]?.name}_rows`]
    this.treeProps.row.rowArray = rowsGroup.map((item) => `${item.name}_rows`)
    this.treeProps.col.colArray = cols.reduce((col, item) => {
      const repeatGroup = col.filter((item) => item === `${item.name}_cols`)
      const colItem = repeatGroup.length ? repeatGroup.length : ''
      col = [...col, `${item.name}_cols${colItem}`]
      return col
    }, [])

    this.treeProps.metrics.metricsAgg = metrics.map((l) => l.agg)
    this.treeProps.metrics.metricsSelect = metrics.reduce((result, item) => {
      result[`${item.agg}(${item.name.split('@')[0]})`] =
        item.total?.totalType || []
      return result
    }, {})
    const {
      row: { rowArray },
      col: { colArray },
      metrics: { metricsSelect },
      root: { rootKey }
    } = this.treeProps
    this.treeProps.list.initWideList = data.reduce((selects, node) => {
      node = [...rowArray, ...colArray, ...Object.keys(metricsSelect)].reduce(
        (item, key) => {
          item[key] = node[removeRowColPrx(key)]
          return item
        },
        {}
      )
      return (selects = [...selects, node])
    }, [])
    this.treeProps.root.rootTree = null
    this.treeProps.metrics.metricsNodeList = []
    this.treeProps.list.totalWideList = []
    this.treeProps.row = {
      ...this.treeProps.row,
      rowLast: rowArray[rowArray.length - 1],
      rootRowArray: [...rowArray, rootKey]
    }
    this.treeProps.col.colLast = colArray[colArray.length - 1]
    this.treeProps.metrics.metricsText = Object.keys(metricsSelect) || []
    this.treeProps.list.keyOrder = [
      ...rowArray,
      ...colArray,
      ...Object.keys(metricsSelect)
    ]
  }

  public getSortSumNode(rows, rowKeys) {
    const breakFn = (rowKeys, idx) => {
      const levelSortKey = rowKeys.reduce((pre, cur) => {
        return (pre = Array.from(new Set([...pre, cur[idx]])))
      }, [])
      const sumText = levelSortKey.findIndex((key) =>
        [SumText.Sum, SumText.Sub].includes(key)
      )
      levelSortKey.push(...levelSortKey.splice(sumText, 1))
      let partGroup = levelSortKey.reduce((pre, cur) => {
        const group = rowKeys.filter((item) => item[idx] === cur)
        return (pre = [...pre, group])
      }, [])
      if (idx == rows.length - 2) {
        const exitedSumGroup = partGroup.splice(0, partGroup.length)
        exitedSumGroup.forEach((group, index) => {
          const sumText = exitedSumGroup[index].findIndex((k) =>
            [SumText.Sum, SumText.Sub].includes(k[k.length - 1])
          )
          exitedSumGroup[index].push(
            ...exitedSumGroup[index].splice(sumText, 1)
          )
        })
        partGroup = [...exitedSumGroup, ...partGroup]
      }
      return partGroup
    }

    const iteration = (rowKeys, idx: number) => {
      if (!idx) return breakFn(rowKeys, idx)
      rowKeys = rowKeys.reduce((arr, item) => {
        const isArray = (group) => {
          return group.every((item) => Array.isArray(item))
        }
        if (!isArray(item.flat(1))) return (arr = [...arr, breakFn(item, idx)])
        const group = iteration(item, idx)
        return (arr = [...arr, group])
      }, [])
      return rowKeys
    }

    const getPartGroupByKey = (divideGroupByLevel, index) => {
      while (index <= Math.max(rows.length - 2, 0)) {
        divideGroupByLevel = iteration(divideGroupByLevel, index)
        index++
      }
      return divideGroupByLevel
    }

    const result = getPartGroupByKey(rowKeys, 0)

    const flatItem = (result) => {
      while (!result[0].every((d) => !Array.isArray(d))) {
        result = result.reduce((pre, cur) => {
          return (pre = [...pre, ...cur])
        }, [])
      }
      return result
    }
    return flatItem(result)
  }

  public getLevelType(node, type) {
    return node.levelType === CategoryType[type]
  }

  public getTraverseBF(callback) {
    const queue = []
    let found = false
    queue.push(this.treeProps.root.rootTree)
    let currentNode = queue.shift()

    while (!found && currentNode) {
      found = !!callback(currentNode)
      if (!found) {
        queue.push(...currentNode.children)
        currentNode = queue.shift()
      }
    }
    return found
  }

  public getContains(callback, traversal) {
    traversal.call(this, callback)
  }

  public getAddToData(obj, toData) {
    const {
      metrics: { metricsText }
    } = this.treeProps

    const node = new Node(obj)
    node.set(obj, metricsText)
    if (this.treeProps.root.rootTree === null) {
      this.treeProps.root.rootTree = node
      return this
    }
    const exitCallBack = function (currentNode) {
      if (currentNode.key === node.key && currentNode.label === node.label) {
        return true
      }
    }
    const exitTag = tree.getTraverseBF.call(this, exitCallBack)
    if (exitTag) {
      return this
    }

    let parent = null
    const callback = (node) => {
      if (node.key === toData.key) {
        parent = node
        return true
      }
    }
    this.getContains(callback, tree.getTraverseBF)
    if (parent) {
      parent.children.push(node)
      node.parent = parent
      return this
    } else {
      throw new Error()
    }
  }

  private getParentId(levelGroup, options) {
    const { listNumber, samePathNode, levelCount } = options
    if (samePathNode) {
      return options.samePathNode.parentKey
    } else {
      return (
        listNumber &&
        levelCount &&
        tree.getLevelGroupNodeParent(levelGroup, options, levelCount).key
      )
    }
  }

  public getNodeLevelType(levelKey) {
    const {
      col: { colArray },
      row: { rootRowArray }
    } = this.treeProps
    if (rootRowArray.includes(levelKey)) {
      return CategoryType.Row
    } else if (colArray.includes(levelKey)) {
      return CategoryType.Col
    } else {
      return CategoryType.Metrics
    }
  }

  public getNodeKey(options) {
    const {
      root: { rootTree }
    } = this.treeProps
    const { listNumber, levelCount, levelKey, listItem } = options
    if (!listNumber) {
      options.samePathNode = null
    } else if (!levelCount) {
      options.samePathNode = rootTree
    } else {
      const queue = [rootTree]
      let currentNode = queue[0]
      while (levelCount !== currentNode.levelCount) {
        queue.push(...currentNode.children)
        currentNode = queue.shift()
      }
      const listItemPath = Object.values(listItem).splice(1, levelCount)
      options.samePathNode = queue.find((item) => {
        let itemPath = []
        while (item.parent) {
          itemPath.unshift(item.label)
          item = item.parent
        }
        return itemPath.toString() == listItemPath.toString()
      })
    }
    const { samePathNode } = options
    return samePathNode ? samePathNode.key : `${levelKey}_${listNumber}`
  }

  public getNodeLabel(nodeProperty, options) {
    const { levelKey, listItem } = options
    if (this.getLevelType(nodeProperty, 'Metrics')) {
      return levelKey
    } else {
      return listItem[levelKey]
    }
  }

  public getLevelGroupNodeParent(levelGroup, levelItem, index) {
    const {
      metrics: { metricsAgg },
      col: { colArray },
      row: { rowArray }
    } = this.treeProps
    if (this.getLevelType(levelItem, 'Metrics')) {
      const rowColConcat = [...rowArray, ...colArray]
      return levelGroup.find(
        (item) => item.originKey == rowColConcat[rowColConcat.length - 1]
      )
    } else {
      return levelGroup[index - 1]
    }
  }

  public getMultiwayTree() {
    const {
      root: { rootLevel },
      list: { initWideList }
    } = this.treeProps
    initWideList.forEach((listItem, listNumber) => {
      const levelGroup = []
      listItem = { ...rootLevel, ...listItem }
      Object.keys(listItem).forEach((levelKey, levelCount) => {
        const options = {
          listItem,
          levelKey,
          listNumber,
          levelCount,
          samePathNode: null
        }

        let nodeProperty = {
          levelKey,
          levelCount,
          key: tree.getNodeKey(options),
          originKey: null,
          label: null,
          sumType: null,
          sumLastNode: false,
          sumNode: false,
          levelType: tree.getNodeLevelType(levelKey),
          parentKey: tree.getParentId(levelGroup, options)
        }
        nodeProperty = {
          ...nodeProperty,
          originKey: getOriginKey(nodeProperty.key),
          label: tree.getNodeLabel(nodeProperty, options)
        }
        if (this.getLevelType(nodeProperty, 'Metrics')) {
          nodeProperty[levelKey] = listItem[levelKey]
        }
        levelGroup.push(nodeProperty)
      })
      levelGroup.forEach((levelItem, index) => {
        const parent = tree.getLevelGroupNodeParent(
          levelGroup,
          levelItem,
          index
        )
        tree = tree.getAddToData(levelItem, parent)
      })
    })
  }

  public getPartBranch(node) {
    const { row, root } = this.treeProps
    const sumText = [SumText.Sub, SumText.Sum]
    if (node.originKey === row.rowLast) {
      while (
        root.rootKey !== node.originKey &&
        !(sumText.includes(node.label) && !sumText.includes(node.parent.label))
      ) {
        node = node.parent
      }
      return node.parent.children
    }
  }

  public getChildGroup(item) {
    const queue = [item]
    let currentNode = queue.shift()
    const { colArray } = this.treeProps.col
    while (currentNode && currentNode.originKey !== colArray[0]) {
      queue.push(...currentNode.children)
      currentNode = queue.shift()
    }
    return [...queue, currentNode]
  }

  public getMergePartBranch(node) {
    const {
      metrics: { metricsNameCol }
    } = this.treeProps
    const AggGroup = []
    const Branch = tree.getPartBranch(node)
    Branch.forEach((item) => {
      const Child = tree.getChildGroup(item)
      Child.forEach((node) => {
        let startNode = new Node(cloneDeep(node))
        const getSameLabel = (child, startNode) => {
          return child.find((item) => item.label == startNode.label)
        }
        let origin = getSameLabel(AggGroup, startNode)
        if (origin) {
          if (origin.originKey == metricsNameCol) {
            startNode.children.forEach((item) => {
              if (!getSameLabel(origin.children, item)) {
                origin.children.push(item)
              }
            })
            return
          } else {
            while (origin && startNode && origin.label == startNode.label) {
              startNode = startNode.children[0]
              origin =
                getSameLabel(origin.children, startNode) || origin.children[0]
            }
            if (!origin && !startNode) return
            return origin.parent.children.push(startNode)
          }
        } else {
          AggGroup.push(startNode)
        }
      })
    })
    return AggGroup
  }

  public copyAggNormalNode(copyParems, AggGroup) {
    const { deepCopy, parentNode, currentNode } = copyParems
    const group = AggGroup || currentNode
    return group.reduce((sumNode, node) => {
      if (parentNode.originKey == this.treeProps.col.colLast) {
        return sumNode
      } else {
        const polyNormalNode = deepCopy(
          { currentNode: node, parentNode },
          { isLastSumNode: false }
        )
        return sumNode.concat(polyNormalNode)
      }
    }, [])
  }

  public getStartNotSumColAndRow(node) {
    const {
      col: { colArray }
    } = this.treeProps
    while (
      node.sumNode &&
      !(
        (isSumLastNode(node.key) || isSumNodeEnd(node.key)) &&
        node.originKey == colArray[0]
      )
    ) {
      node = node.parent
    }
    return node
  }

  public decideSumBranchType(node) {
    const {
      root: { rootKey },
      col: { colArray },
      row: { rowArray },
      metrics: { metricsSelect }
    } = this.treeProps
    if (Object.keys(metricsSelect).includes(node.originKey)) {
      return null
    }
    const isBeiginNoneParentSumKey = tree.getStartNotSumColAndRow(node)
      .originKey
    const isBeiginSumLastNode = tree.getStartNotSumColAndRow(node).sumLastNode
    let subType = []

    if (isBeiginNoneParentSumKey === rootKey) {
      subType.push(SumType.ColTotal)
    } else if (
      isBeiginNoneParentSumKey === colArray[0] &&
      isBeiginSumLastNode
    ) {
      subType.push(SumType.RowTotal)
    } else if (
      !isBeiginSumLastNode &&
      colArray.includes(isBeiginNoneParentSumKey)
    ) {
      subType.push(SumType.RowSubTotal)
    } else if (
      isBeiginNoneParentSumKey !== rootKey &&
      rowArray.includes(isBeiginNoneParentSumKey)
    ) {
      subType.push(SumType.ColSubTotal)
    } else {
    }
    return subType
  }

  public decideSumNodeKeyTextDisplay(options) {
    const { nodeValue, isLastSumNode, indexNumber, currentNode } = options
    if (this.getLevelType(currentNode, 'Col') && isLastSumNode) {
      return `${nodeValue}sumLastNode`
    } else {
      return `${nodeValue}${indexNumber}sumNode`
    }
  }
  public decideSumAttribute(options) {
    const {
      newNode,
      key,
      deepCopy,
      parentNode,
      nodeValue,
      isLastSumNode
    } = options
    switch (key) {
      case 'levelCount':
        newNode[key] = parentNode[key] + 1
      case 'parent':
        newNode[key] = parentNode
        break
      case 'parentKey':
        newNode[key] = parentNode.key
        break
      case 'key':
        newNode[key] = tree.decideSumNodeKeyTextDisplay(options)
        break
      case 'originKey':
        newNode[key] = getOriginKey(newNode.key)
        break
      case 'sumLastNode':
        newNode[key] = !!isSumLastNode(newNode.key)
        break
      case 'sumNode':
        newNode[key] = isSumNodeEnd(newNode.key)
        break
      case 'levelType':
        newNode[key] = tree.getNodeLevelType(newNode.originKey)
        break
      case 'sumType':
        newNode[key] = tree.decideSumBranchType(newNode)
        break
      case 'label':
        newNode[key] = tree.decideSumOrSubSumTextDisplay(options)
        break
      case 'children':
        newNode[key] = tree.copyIteration(
          deepCopy,
          nodeValue,
          newNode,
          isLastSumNode
        )
        break
      default:
        newNode[key] = null
    }
  }

  public decideSumOrSubSumTextDisplay(options) {
    const { nodeValue, isLastSumNode, newNode } = options
    const totalTypes = tree.decideSumBranchType(newNode)
    if (isLastSumNode && totalTypes) {
      const Sum = [SumType.ColTotal, SumType.RowTotal].includes(...totalTypes)
      return Sum ? SumText.Sum : SumText.Sub
    } else {
      return nodeValue
    }
  }

  public copyIteration(
    deepCopy,
    currentNode,
    parentNode,
    isLastSumNode = false
  ) {
    return deepCopy({ currentNode, parentNode }, { isLastSumNode })
  }

  public copyAggNoramlChild(copyParems) {
    const {
      col: { colArray },
      row: { rowLast }
    } = this.treeProps
    const { parentNode, newNode, isLastSumNode } = copyParems
    const normalSumNode = !isLastSumNode && this.getLevelType(parentNode, 'Col')
    let AggGroup
    if (parentNode.originKey === rowLast && colArray.length) {
      AggGroup = tree.getMergePartBranch(parentNode)
    }
    if (AggGroup || normalSumNode) {
      return tree.copyAggNormalNode(copyParems, AggGroup)
    }
    return newNode
  }

  public copyTotalNode(currentNode, parentNode) {
    let indexNumber = 0
    const deepCopy = (copyNode, copyOptions) => {
      indexNumber++
      const { currentNode, parentNode } = copyNode
      const { isLastSumNode = true } = copyOptions
      const {
        metrics: { metricsText },
        col: { colLast },
        row: { rowLast }
      } = this.treeProps
      if (typeof currentNode !== 'object' || !currentNode) {
        return currentNode
      }

      let newNode
      if (Array.isArray(currentNode)) {
        newNode = []
      } else {
        newNode = new Node({})
        newNode.set({}, metricsText)
      }
      const copyParems = {
        deepCopy,
        ...copyNode,
        ...copyOptions,
        newNode,
        indexNumber,
        isLastSumNode
      }
      if (currentNode.length) {
        newNode = tree.copyAggNoramlChild(copyParems)
        if (parentNode.originKey === (colLast || rowLast)) {
          currentNode.forEach((k) => {
            const copyNode = tree.copyIteration(deepCopy, k, parentNode, true)
            newNode.push(copyNode)
          })
        } else {
          const copyNode = tree.copyIteration(
            deepCopy,
            currentNode[0],
            parentNode,
            true
          )
          newNode.push(copyNode)
        }
      } else {
        const baseKey = [
          'levelCount',
          'parent',
          'parentKey',
          'key',
          'originKey',
          'sumLastNode',
          'sumNode',
          'levelType',
          'label',
          'sumType',
          'children',
          ...metricsText
        ]
        !Array.isArray(currentNode) &&
          baseKey.forEach((attr) => {
            const exitedVal = Array.isArray(newNode[attr])
              ? newNode[attr].length
              : newNode[attr]
            if (exitedVal) {
              return
            }
            const nodeValue = currentNode[attr]
            const options = { nodeValue, key: attr, ...copyParems }
            tree.decideSumAttribute(options)
          })
      }
      return newNode
    }
    return tree.copyIteration(deepCopy, currentNode, parentNode, true)
  }

  public getSumMultiwayTree() {
    const queue = [this.treeProps.root.rootTree]
    let currentNode = queue.shift()
    const {
      row: { rowArray },
      col: { colArray },
      root: { rootKey }
    } = this.treeProps
    const rowColConcat = [...rowArray, ...colArray]
    rowColConcat.splice(rowColConcat.length - 1, 1, rootKey)
    while (currentNode && rowColConcat.includes(currentNode.originKey)) {
      queue.push(...currentNode.children)
      currentNode.children.push(
        tree.copyTotalNode(currentNode.children[0], currentNode)
      )
      currentNode = queue.shift()
    }
  }

  public getNodePathSumType(currentNode) {
    let pathSumTypeGroup = []
    while (currentNode.parent) {
      if ([SumText.Sum, SumText.Sub].includes(currentNode.label)) {
        pathSumTypeGroup = currentNode.sumType
          ? [...pathSumTypeGroup, ...currentNode.sumType]
          : pathSumTypeGroup
      }
      currentNode = currentNode.parent
    }
    return Array.from(new Set(pathSumTypeGroup))
  }

  public getMetricNodeList() {
    const {
      root: { rootTree },
      metrics: { metricsText, metricsNodeList }
    } = this.treeProps
    const queue = [rootTree]
    let currentNode = queue.shift()
    queue.push(...currentNode.children)
    while (queue.length) {
      currentNode = queue.shift()
      if (metricsText.includes(currentNode.label)) {
        currentNode.sumType = tree.getNodePathSumType(currentNode)
        metricsNodeList.push(currentNode)
      }
      queue.push(...currentNode.children)
    }
  }

  public getUnSumNodeReduceSumMetrics(option) {
    const { children, key, callback } = option
    const {
      row: { rowLast },
      col: { colLast }
    } = this.treeProps
    const colLastNode = children[0].parent.originKey === (colLast || rowLast)
    const selectChild = children.filter((item) => {
      if (colLastNode) {
        return callback(item) && item.originKey === key
      } else {
        return callback(item)
      }
    })
    return selectChild.reduce((number, node) => {
      return (number = Number(number) + node[key])
    }, 0)
  }

  public getSumMetricDFS() {
    const {
      metrics: { metricsNodeList, metricsText, metricsSelect }
    } = this.treeProps
    metricsNodeList.forEach((node: ITreeNodeProperty) => {
      if (node.sumNode) {
        const getFirstNonSumParent = (
          origin: ITreeNodeProperty,
          path: Array<string>
        ) => {
          while (origin.sumNode) {
            path.unshift(origin.label)
            origin = origin.parent
          }
          return {
            from: origin,
            path
          }
        }
        const { from, path } = getFirstNonSumParent(node, [])
        metricsText.forEach((key: string) => {
          const needSum = node.sumType.some((item) =>
            metricsSelect[node.originKey].includes(item)
          )
          if (needSum) {
            tree.matchSameNodeSum(from.children, key, path)
          }
        })
      } else {
        while (node) {
          const callback = (node) => !node.sumNode
          if (metricsText.includes(node.originKey)) {
            node[node.originKey] = node[node.originKey]
          } else {
            metricsText.forEach((key) => {
              const option = {
                children: node.children,
                key,
                callback
              }
              node[key] = tree.getUnSumNodeReduceSumMetrics(option)
            })
          }
          node = node.parent
        }
        return
      }
    })
  }

  public matchSameNodeSum(
    currentQueue: Array<ITreeNodeProperty>,
    key: string,
    path: Array<string>
  ) {
    let level: number = 0
    let needSumNodeGroup: Array<ITreeNodeProperty> = []
    let currentLevelSumNode: ITreeNodeProperty = currentQueue.find(
      (node) => node.sumNode
    )
    while (currentQueue.length) {
      needSumNodeGroup = currentQueue.filter((node) => {
        if ([SumText.Sum, SumText.Sub].includes(path[level])) {
          return node.label !== path[level]
        } else {
          return node.label == path[level]
        }
      })
      const callback = (node) => !node.sumNode
      const option = {
        children: needSumNodeGroup,
        key,
        callback
      }
      currentLevelSumNode[key] = tree.getUnSumNodeReduceSumMetrics(option)
      level++
      currentQueue = needSumNodeGroup.reduce(
        (array: Array<ITreeNodeProperty>, item: ITreeNodeProperty) => {
          return array.concat(item.children)
        },
        []
      )
      currentLevelSumNode = currentLevelSumNode.children.find(
        (item) => item.label === path[level]
      )
    }
  }

  public getTotalWideTableList(props) {
    tree.getInitProps(props)
    tree.getMultiwayTree()
    tree.getSumMultiwayTree()
    tree.getMetricNodeList()
    tree.getSumMetricDFS()
    console.log(tree, 'tree的值')
    return tree.getTotalWideTableJson()
  }
}

let tree = new MultiwayTree()

export default tree
