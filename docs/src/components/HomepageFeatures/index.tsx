import clsx from 'clsx';
import Heading from '@theme/Heading';
import styles from './styles.module.css';

type FeatureItem = {
  title: string;
  Svg: React.ComponentType<React.ComponentProps<'svg'>>;
  description: JSX.Element;
};

const FeatureList: FeatureItem[] = [
  {
    title: 'Zero Native Dependencies',
    Svg: require('@site/static/img/icon-zero-deps.svg').default,
    description: (
      <>
        Run WebAssembly anywhere the JVM runs — no JNI, no native libraries,
        no platform-specific binaries. Ship a single JAR to every OS and architecture.
      </>
    ),
  },
  {
    title: 'Sandboxed by Default',
    Svg: require('@site/static/img/icon-sandbox.svg').default,
    description: (
      <>
        Wasm modules execute in an isolated sandbox with no ambient capabilities.
        Your host controls what the guest can access — memory, files, and system calls.
      </>
    ),
  },
  {
    title: 'Drop-in Integration',
    Svg: require('@site/static/img/icon-integration.svg').default,
    description: (
      <>
        Add a single Maven dependency to embed Wasm in your Java app.
        Choose between interpreter, runtime compiler, or build-time compiler
        depending on your needs.
      </>
    ),
  },
];

function Feature({title, Svg, description}: FeatureItem) {
  return (
    <div className={clsx('col col--4')}>
      <div className="text--center">
        <Svg className={styles.featureSvg} role="img" />
      </div>
      <div className="text--center padding-horiz--md">
        <Heading as="h3">{title}</Heading>
        <p>{description}</p>
      </div>
    </div>
  );
}

export default function HomepageFeatures(): JSX.Element {
  return (
    <section className={styles.features}>
      <div className="container">
        <div className="row">
          {FeatureList.map((props, idx) => (
            <Feature key={idx} {...props} />
          ))}
        </div>
      </div>
    </section>
  );
}
