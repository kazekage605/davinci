import React from 'react'
import { fromJS } from 'immutable'
import { IFieldTotalConfig } from './types'
import { getDefaultTotalConfig } from './util'
import { ViewModelVisualTypes } from 'containers/View/constants'
import { TotalTypes, TotalTypesLocale, TotalTypesSetting } from './constants'

import { FormComponentProps } from 'antd/lib/form/Form'
import { Form, Checkbox, Select, Button, Modal } from 'antd'
const FormItem = Form.Item
const { Option } = Select

interface ITotalConfigFormProps extends FormComponentProps {
  visible: boolean
  visualType: ViewModelVisualTypes
  totalConfig: IFieldTotalConfig
  onCancel: () => void
  onSave: (config: IFieldTotalConfig) => void
}

interface ITotalConfigFormStates {
  localConfig: IFieldTotalConfig
}

class TotalConfigModal extends React.PureComponent<
  ITotalConfigFormProps,
  ITotalConfigFormStates
> {

  public constructor(props: ITotalConfigFormProps) {
    super(props)
    const { totalConfig } = props

    this.state = {
      localConfig: totalConfig
        ? fromJS(totalConfig).toJS()
        : getDefaultTotalConfig()
    }
  }

  public componentDidMount() {
    this.props.form.setFieldsValue(this.state.localConfig)
  }

  public componentWillReceiveProps(nextProps: ITotalConfigFormProps) {
    const { totalConfig, form } = nextProps
    if (totalConfig === this.props.totalConfig) {
      return
    }

    this.setState(
      {
        localConfig: totalConfig
          ? fromJS(totalConfig).toJS()
          : getDefaultTotalConfig()
      },
      () => {
        form.setFieldsValue(this.state.localConfig)
      }
    )
  }

  private onTotalTypeChange = (e) => {
    const { localConfig } = this.state
    const selectedTotalType = e
    const previousValues = this.props.form.getFieldsValue() as IFieldTotalConfig
    const nextLocalConfig: IFieldTotalConfig = {
      ...localConfig,
      ...previousValues,
      totalType: selectedTotalType
    }
    this.setState({
      localConfig: nextLocalConfig
    })
  }

  private renderFormatTypes() {
    const { form, visualType } = this.props
    const { getFieldDecorator } = form
    const formatTypesGroup = TotalTypesSetting[visualType] && (
      <FormItem>
        {getFieldDecorator('totalType', {
          initialValue: []
        })(
          <Checkbox.Group onChange={this.onTotalTypeChange}>
            {TotalTypesSetting[visualType].map((totalType) => (
              <Checkbox key={totalType} value={totalType}>
                {TotalTypesLocale[totalType]}
              </Checkbox>
            ))}
          </Checkbox.Group>
        )}
      </FormItem>
    )
    return formatTypesGroup
  }

  private save = () => {
    const { form } = this.props

    form.validateFieldsAndScroll((err, totalValues) => {
      if (err) {
        return
      }
      const totalType = totalValues['totalType']
      const config: IFieldTotalConfig = {
        totalType
      }
      this.props.onSave(config)
    })
  }

  private cancel = () => {
    this.props.onCancel()
  }

  private modalFooter = [
    <Button key="cancel" size="large" onClick={this.cancel}>
      取 消
    </Button>,
    <Button key="submit" size="large" type="primary" onClick={this.save}>
      保 存
    </Button>
  ]

  public render() {
    const { visible } = this.props
    return (
      <Modal
        title="总和设置"
        wrapClassName="ant-modal-small"
        footer={this.modalFooter}
        visible={visible}
        onCancel={this.cancel}
        onOk={this.save}
      >
        <Form>{this.renderFormatTypes()}</Form>
      </Modal>
    )
  }
}

export default Form.create<ITotalConfigFormProps>()(TotalConfigModal)
