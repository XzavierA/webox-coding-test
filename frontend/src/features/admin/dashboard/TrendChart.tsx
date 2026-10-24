import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { AriaComponent, GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { SVGRenderer } from 'echarts/renderers';
import { useEffect, useRef } from 'react';
import type { Dashboard } from '../../../api/types';
import { moneyToFen } from '../../../shared/money';

echarts.use([LineChart, AriaComponent, GridComponent, LegendComponent, TooltipComponent, SVGRenderer]);

export function TrendChart({ data }: { data: Dashboard['trend'] }) {
  const chartRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const element = chartRef.current;
    if (!element || element.clientWidth === 0 || element.clientHeight === 0) return;
    const chart = echarts.init(element, undefined, { renderer: 'svg' });
    chart.setOption({
      aria: { enabled: true, description: 'Seven day order count and revenue trend.' },
      color: ['#386b52', '#d08a45'],
      tooltip: { trigger: 'axis' },
      legend: { data: ['Orders', 'Revenue'] },
      grid: { left: 48, right: 56, top: 48, bottom: 34 },
      xAxis: { type: 'category', data: data.map((point) => point.date.slice(5)), boundaryGap: false },
      yAxis: [{ type: 'value', name: 'Orders', minInterval: 1 }, { type: 'value', name: 'Revenue (¥)', min: 0 }],
      series: [
        { name: 'Orders', type: 'line', smooth: true, data: data.map((point) => point.orders) },
        { name: 'Revenue', type: 'line', yAxisIndex: 1, smooth: true, data: data.map((point) => moneyToFen(point.revenue) / 100) },
      ],
    });
    const resize = () => chart.resize();
    window.addEventListener('resize', resize);
    return () => { window.removeEventListener('resize', resize); chart.dispose(); };
  }, [data]);
  return <div ref={chartRef} className="trend-chart" role="img" aria-label="Seven day order and revenue chart" />;
}
