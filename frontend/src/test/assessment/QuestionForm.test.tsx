import { render,screen } from '@testing-library/react';
import { describe,expect,it,vi } from 'vitest';
import { QuestionForm } from '../../features/assessment/builder/QuestionForm';
import { blankItem } from '../../features/assessment/model/doc';

describe('Question form accessible fields',()=>{
  it('keeps labels independent of the initial nonempty textarea value',()=>{
    render(<QuestionForm item={blankItem('Q07','q1')} label="Câu 1" assets={{}} onChange={vi.fn()} onType={vi.fn()} onDuplicate={vi.fn()} onRemove={vi.fn()}/>);
    expect(screen.getByRole('textbox',{name:'Đề bài'})).toHaveValue('___');
    expect(screen.getByLabelText('Chỗ trống 1: đáp án chấp nhận, mỗi dòng một cách',{exact:true})).toBeInstanceOf(HTMLTextAreaElement);
  });
});
